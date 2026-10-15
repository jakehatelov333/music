package cn.edu.seig.vibemusic.service;

import cn.edu.seig.vibemusic.constant.MediaCleanupSourceType;
import cn.edu.seig.vibemusic.mapper.MediaCleanupTaskMapper;
import cn.edu.seig.vibemusic.model.entity.MediaCleanupTask;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 清理任务的并发与租约语义（真实 SQL 条件更新，Mockito 层测不到）。
 *
 * <p>这份测试钉住的是"进程崩溃后能不能恢复、旧执行者会不会覆盖新结果"：
 * <ul>
 *     <li>领取必须是<b>原子条件更新</b>：租约仍有效的任务不能被第二个人领走；</li>
 *     <li>租约过期后必须能被重新领取，且 {@code generation} 自增；</li>
 *     <li>旧代次的回写必须被拒绝——否则被抢占的执行者会覆盖新执行者的结论；</li>
 *     <li>同一业务操作重复登记只产生一条任务。</li>
 * </ul>
 *
 * <p>H2 足以验证条件更新的语义；{@code utf8mb4_bin} 排序规则与 {@code ON DUPLICATE KEY UPDATE}
 * 的真实 MySQL 行为已在隔离库单独验证过（H2 测不出排序规则差异）。
 */
@SpringJUnitConfig(MediaCleanupSqlConcurrencyTest.Config.class)
class MediaCleanupSqlConcurrencyTest {

    private static final String BUCKET = "vibe-music-data";

    @org.springframework.beans.factory.annotation.Autowired
    MediaCleanupTaskMapper mapper;
    JdbcTemplate jdbc;

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(Config.dataSource());
        jdbc.execute("DELETE FROM tb_media_cleanup_task");
    }

    private void enqueue(String key, String sourceRef) {
        // 与生产一致：立刻到期（生产侧传 now - 1s，避免登记/领取落在同一微秒时当次领不到）
        LocalDateTime now = LocalDateTime.now();
        mapper.enqueue(BUCKET, key, MediaCleanupService.sha256Hex(key),
                MediaCleanupSourceType.SONG_DELETE, sourceRef, "test", now, now.minusSeconds(1));
    }

    private Long onlyTaskId() {
        return jdbc.queryForObject("SELECT id FROM tb_media_cleanup_task", Long.class);
    }

    private void forceLeaseExpired(Long id) {
        jdbc.update("UPDATE tb_media_cleanup_task SET lease_expires_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(1), id);
    }


    private String status(Long id) {
        return jdbc.queryForObject("SELECT status FROM tb_media_cleanup_task WHERE id = ?", String.class, id);
    }

    private long generation(Long id) {
        Long g = jdbc.queryForObject("SELECT generation FROM tb_media_cleanup_task WHERE id = ?", Long.class, id);
        return g == null ? -1 : g;
    }

    @Test
    @DisplayName("同一业务操作重复登记只产生一条任务；不同对象键各成一条")
    void duplicateEnqueueIsIdempotent() {
        enqueue("songs/a.mp3", "5");
        enqueue("songs/a.mp3", "5");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_media_cleanup_task", Integer.class));

        enqueue("songs/b.mp3", "5");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM tb_media_cleanup_task", Integer.class));

        // 同 key 不同来源也是两条（例如专辑与歌曲共用封面时各自登记）
        enqueue("songs/a.mp3", "6");
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM tb_media_cleanup_task", Integer.class));
    }

    @Test
    @DisplayName("重复登记不改变已有状态：已被处理过的任务不会被重新激活")
    void duplicateEnqueueDoesNotResetState() {
        enqueue("songs/a.mp3", "5");
        Long id = onlyTaskId();
        LocalDateTime now = LocalDateTime.now();
        int claimed = mapper.claim(id, "worker-1", now, now.plusMinutes(1));
        long gen = generation(id);
        int marked = mapper.markSucceeded(id, "worker-1", gen, now);
        if (claimed != 1 || marked != 1) {
            System.out.println("[诊断] claimed=" + claimed + " marked=" + marked + " gen=" + gen + " now=" + now);
            jdbc.queryForList("SELECT id,status,next_run_at,lease_owner,generation FROM tb_media_cleanup_task")
                    .forEach(r -> System.out.println("[诊断] " + r));
        }

        enqueue("songs/a.mp3", "5");

        assertEquals(MediaCleanupTask.STATUS_SUCCEEDED, status(id));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_media_cleanup_task", Integer.class));
    }

    @Test
    @DisplayName("未到期的任务不会被领取；租约仍有效时第二个人抢不走")
    void doesNotClaimTaskWithValidLease() {
        enqueue("songs/a.mp3", "5");
        Long id = onlyTaskId();
        LocalDateTime now = LocalDateTime.now();

        assertEquals(1, mapper.claim(id, "worker-1", now, now.plusSeconds(60)));
        assertEquals(MediaCleanupTask.STATUS_RUNNING, status(id));
        assertEquals(1L, generation(id));

        // 租约未到期 → 不在候选列表里，也抢不走
        assertTrue(mapper.findDueIds(LocalDateTime.now(), 100).isEmpty());
        assertEquals(0, mapper.claim(id, "worker-2", LocalDateTime.now(), LocalDateTime.now().plusSeconds(60)));
        assertEquals(1L, generation(id));
    }

    @Test
    @DisplayName("租约过期后可被重新领取，且代次自增")
    void reclaimsTaskAfterLeaseExpires() {
        enqueue("songs/a.mp3", "5");
        Long id = onlyTaskId();
        LocalDateTime now = LocalDateTime.now();
        mapper.claim(id, "worker-1", now, now.plusSeconds(60));

        forceLeaseExpired(id);

        assertEquals(List.of(id), mapper.findDueIds(LocalDateTime.now(), 100));
        assertEquals(1, mapper.claim(id, "worker-2", LocalDateTime.now(), LocalDateTime.now().plusSeconds(60)));
        assertEquals(2L, generation(id));
    }

    @Test
    @DisplayName("旧代次不能覆盖新代次的结果（被抢占的执行者回写被拒绝）")
    void staleGenerationCannotOverwriteNewerResult() {
        enqueue("songs/a.mp3", "5");
        Long id = onlyTaskId();
        LocalDateTime now = LocalDateTime.now();
        mapper.claim(id, "worker-1", now, now.plusSeconds(60));
        long staleGeneration = generation(id);

        forceLeaseExpired(id);
        mapper.claim(id, "worker-2", LocalDateTime.now(), LocalDateTime.now().plusSeconds(60));

        // 旧执行者（gen=1、owner=worker-1）的回写必须影响 0 行
        assertEquals(0, mapper.markSucceeded(id, "worker-1", staleGeneration, LocalDateTime.now()));
        assertEquals(0, mapper.markRetained(id, "worker-1", staleGeneration, LocalDateTime.now()));
        assertEquals(MediaCleanupTask.STATUS_RUNNING, status(id));

        // 新执行者可以正常回写
        assertEquals(1, mapper.markSucceeded(id, "worker-2", generation(id), LocalDateTime.now()));
        assertEquals(MediaCleanupTask.STATUS_SUCCEEDED, status(id));
    }

    @Test
    @DisplayName("领取后进程崩溃（不回写）：租约到期后任务仍能被恢复执行")
    void recoversTaskAfterExecutorCrash() {
        enqueue("songs/a.mp3", "5");
        Long id = onlyTaskId();
        LocalDateTime now = LocalDateTime.now();
        mapper.claim(id, "crashed-worker", now, now.plusSeconds(60));
        // 模拟崩溃：不回写任何状态

        assertTrue(mapper.findDueIds(LocalDateTime.now(), 100).isEmpty(), "租约未到期不应被抢");
        forceLeaseExpired(id);
        assertEquals(List.of(id), mapper.findDueIds(LocalDateTime.now(), 100));
        assertEquals(1, mapper.claim(id, "worker-2", LocalDateTime.now(), LocalDateTime.now().plusSeconds(60)));

        MediaCleanupTask recovered = mapper.findById(id);
        assertNotNull(recovered);
        assertEquals(MediaCleanupTask.STATUS_RUNNING, recovered.getStatus());
        assertEquals(2L, recovered.getGeneration());
    }

    @Test
    @DisplayName("未到执行时间的任务不会被领取（退避生效）")
    void doesNotClaimTaskScheduledInTheFuture() {
        enqueue("songs/a.mp3", "5");
        Long id = onlyTaskId();
        jdbc.update("UPDATE tb_media_cleanup_task SET next_run_at = ? WHERE id = ?",
                LocalDateTime.now().plusMinutes(5), id);

        assertTrue(mapper.findDueIds(LocalDateTime.now(), 100).isEmpty());
        assertEquals(0, mapper.claim(id, "worker-1", LocalDateTime.now(), LocalDateTime.now().plusSeconds(60)));
    }

    @Test
    @DisplayName("失败退避后重新变为可领取，且领取时累计尝试次数")
    void retryWaitBecomesClaimableAfterBackoff() {
        enqueue("songs/a.mp3", "5");
        Long id = onlyTaskId();
        LocalDateTime now = LocalDateTime.now();
        mapper.claim(id, "worker-1", now, now.plusSeconds(60));
        long gen = generation(id);

        LocalDateTime nextRun = LocalDateTime.now().plusMinutes(2);
        mapper.markRetryWait(id, "worker-1", gen, nextRun, "minio down", LocalDateTime.now());
        assertEquals(MediaCleanupTask.STATUS_RETRY_WAIT, status(id));
        assertTrue(mapper.findDueIds(LocalDateTime.now(), 100).isEmpty(), "退避期内不应被领取");

        jdbc.update("UPDATE tb_media_cleanup_task SET next_run_at = ? WHERE id = ?",
                LocalDateTime.now().minusSeconds(1), id);
        assertEquals(1, mapper.claim(id, "worker-2", LocalDateTime.now(), LocalDateTime.now().plusSeconds(60)));
        assertEquals(2, mapper.findById(id).getAttempts(), "每次领取累计一次尝试");
    }

    @Configuration
    static class Config {
        private static final JdbcDataSource SOURCE = new JdbcDataSource();

        Config() {
            SOURCE.setURL("jdbc:h2:mem:media_cleanup_sql;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        }

        static DataSource dataSource() {
            return SOURCE;
        }

        @Bean
        DataSource dataSourceBean() {
            return SOURCE;
        }

        @Bean
        MediaCleanupTaskMapper mapper() throws Exception {
            new JdbcTemplate(SOURCE).execute("""
                    CREATE TABLE IF NOT EXISTS tb_media_cleanup_task (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        bucket VARCHAR(64) NOT NULL,
                        object_key VARCHAR(255) NOT NULL,
                        key_digest CHAR(64) NOT NULL,
                        source_type VARCHAR(32) NOT NULL,
                        source_ref VARCHAR(64) NOT NULL,
                        reason VARCHAR(64),
                        status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                        attempts INT NOT NULL DEFAULT 0,
                        next_run_at TIMESTAMP NOT NULL,
                        lease_owner VARCHAR(64),
                        lease_expires_at TIMESTAMP,
                        generation BIGINT NOT NULL DEFAULT 0,
                        last_error VARCHAR(500),
                        created_at TIMESTAMP NOT NULL,
                        updated_at TIMESTAMP NOT NULL,
                        CONSTRAINT uk_media_cleanup UNIQUE (source_type, source_ref, key_digest)
                    )""");
            MybatisConfiguration config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            config.addMapper(MediaCleanupTaskMapper.class);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(SOURCE);
            factory.setConfiguration(config);
            return new SqlSessionTemplate(factory.getObject()).getMapper(MediaCleanupTaskMapper.class);
        }
    }
}
