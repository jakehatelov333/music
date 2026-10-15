package cn.edu.seig.vibemusic.service;

import cn.edu.seig.vibemusic.mapper.AlbumMapper;
import cn.edu.seig.vibemusic.mapper.MediaCleanupTaskMapper;
import cn.edu.seig.vibemusic.mapper.MediaReferenceMapper;
import cn.edu.seig.vibemusic.mapper.SongMapper;
import cn.edu.seig.vibemusic.model.entity.MediaCleanupTask;
import cn.edu.seig.vibemusic.service.impl.AlbumServiceImpl;
import cn.edu.seig.vibemusic.service.impl.SongServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * 共享封面保护与事务边界的端到端验收（内存库跑真实 SQL 与真实事务）。
 *
 * <p>这份测试钉住的是 Phase C 计划书里两个已确认会造成实际损坏的问题：
 * <ol>
 *     <li><b>误删共享封面</b>：一张专辑封面被多首歌共用（真实库里最极端的一个对象被 15 行引用），
 *         删除其中一首绝不能把别人的封面删掉——封面只能由 worker 核对引用后判定 RETAINED；</li>
 *     <li><b>先删文件、后改数据库</b>：业务事务回滚时文件必须还在、任务表必须一并回滚；
 *         反过来，任务表写入失败必须把业务变更一起回滚，不允许出现"记录没了、文件也没了"的孤儿删除。</li>
 * </ol>
 */
@SpringJUnitConfig(SharedCoverDeletionTest.Config.class)
class SharedCoverDeletionTest {

    private static final String SHARED_COVER_KEY = "songCovers/album-9-shared.jpg";

    // 事务代理基于接口（JDK 动态代理），因此这里注入接口类型而不是实现类
    @Autowired cn.edu.seig.vibemusic.service.ISongService songService;
    @Autowired cn.edu.seig.vibemusic.service.IAlbumService albumService;
    @Autowired MediaCleanupService mediaCleanup;
    @Autowired MediaCleanupTaskMapper taskMapper;
    @Autowired SongMapper songMapper;
    @Autowired MediaReferenceMapper referenceMapper;
    @Autowired MinioService minio;
    @Autowired TransactionTemplate tx;
    JdbcTemplate jdbc;

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(Config.dataSource());
        Mockito.reset(minio);
        // mock 的 toObjectName 默认返回 null 会让 enqueue 静默跳过；打上与生产实现等价的解析语义
        Mockito.when(minio.toObjectName(anyString())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            String marker = "/vibe-music-data/";
            int i = url.indexOf(marker);
            return i >= 0 ? url.substring(i + marker.length()) : url;
        });
        jdbc.execute("DROP TABLE IF EXISTS tb_song");
        jdbc.execute("DROP TABLE IF EXISTS tb_album");
        jdbc.execute("DROP TABLE IF EXISTS tb_playlist");
        jdbc.execute("DROP TABLE IF EXISTS tb_artist");
        jdbc.execute("DROP TABLE IF EXISTS tb_user");
        jdbc.execute("DROP TABLE IF EXISTS tb_banner");
        jdbc.execute("DROP TABLE IF EXISTS tb_media_cleanup_task");
        jdbc.execute("CREATE TABLE tb_song(id BIGINT PRIMARY KEY, name VARCHAR(255), album VARCHAR(255), album_id BIGINT, artist_id BIGINT, disc_number INT, track_number INT, lyric CLOB, lyric_source VARCHAR(32), lyric_status INT, lyric_updated_at TIMESTAMP, duration VARCHAR(20), style VARCHAR(255), cover_url VARCHAR(255), audio_url VARCHAR(255), release_time DATE)");
        jdbc.execute("CREATE TABLE tb_album(id BIGINT PRIMARY KEY, name VARCHAR(255), artist_id BIGINT, cover_url VARCHAR(255), release_date VARCHAR(32), description VARCHAR(1000), status VARCHAR(16), created_at TIMESTAMP, updated_at TIMESTAMP)");
        // 引用核对会查全部 7 类引用列，缺一张表就会让核对抛异常 → 任务被误判为失败
        jdbc.execute("CREATE TABLE tb_playlist(id BIGINT PRIMARY KEY, cover_url VARCHAR(255))");
        jdbc.execute("CREATE TABLE tb_artist(id BIGINT PRIMARY KEY, avatar VARCHAR(255))");
        jdbc.execute("CREATE TABLE tb_user(id BIGINT PRIMARY KEY, user_avatar VARCHAR(255))");
        jdbc.execute("CREATE TABLE tb_banner(id BIGINT PRIMARY KEY, banner_url VARCHAR(255))");
        jdbc.execute("""
                CREATE TABLE tb_media_cleanup_task (
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
        // 专辑 9 与三首歌共享同一张封面；音频各自独立
        jdbc.update("INSERT INTO tb_album(id, name, cover_url) VALUES (9, 'Album 9', 'http://127.0.0.1:9005/vibe-music-data/" + SHARED_COVER_KEY + "')");
        for (long id : new long[]{10, 11, 12}) {
            jdbc.update("INSERT INTO tb_song(id, name, album_id, cover_url, audio_url) VALUES (?, 'Song '||?, 9, ?, ?)",
                    id, id, "http://127.0.0.1:9005/vibe-music-data/" + SHARED_COVER_KEY, "songs/audio-" + id + ".mp3");
        }
    }

    private List<MediaCleanupTask> tasksForKey(String key) {
        return jdbc.query("SELECT * FROM tb_media_cleanup_task WHERE object_key = ? ORDER BY id", (rs, i) -> {
            MediaCleanupTask t = new MediaCleanupTask();
            t.setId(rs.getLong("id"));
            t.setObjectKey(rs.getString("object_key"));
            t.setStatus(rs.getString("status"));
            t.setSourceType(rs.getString("source_type"));
            t.setSourceRef(rs.getString("source_ref"));
            return t;
        }, key);
    }

    private void assertTaskStatus(String key, String expected) {
        List<MediaCleanupTask> rows = tasksForKey(key);
        assertFalse(rows.isEmpty(), "应存在 object_key=" + key + " 的清理任务");
        for (MediaCleanupTask row : rows) {
            assertEquals(expected, row.getStatus(), "任务 " + row.getId() + "（" + row.getSourceType() + ":" + row.getSourceRef() + "）");
        }
    }

    /**
     * 等待任务落到终态再断言。
     *
     * <p>提交后 afterCommit 会立刻异步唤醒 worker，与本测试手动触发的 runDueTasks 并发：
     * 领取是原子的，两者不会重复执行，但可能出现"异步线程先抢到租约、本次手动调用领不到"，
     * 也可能出现"本次唤醒被合并丢弃（上一次唤醒还在跑）"。因此这里反复驱动 worker 并轮询终态，
     * 而不是依赖某一次调用的瞬时结果——生产语义只保证最终一致。
     */
    private void awaitTaskStatus(String key, String expected) {
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            mediaCleanup.runDueTasks();
            List<MediaCleanupTask> rows = tasksForKey(key);
            if (!rows.isEmpty() && rows.stream().allMatch(t -> expected.equals(t.getStatus()))) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        System.out.println("[诊断] now=" + java.time.LocalDateTime.now() + " expected=" + expected);
        System.out.println("[诊断] findDueIds=" + taskMapper.findDueIds(java.time.LocalDateTime.now(), 100));
        jdbc.queryForList("SELECT id,status,next_run_at,lease_owner,lease_expires_at,attempts,generation,last_error FROM tb_media_cleanup_task")
                .forEach(r -> System.out.println("[诊断] " + r));
        assertTaskStatus(key, expected);
    }

    private long countRows(String tableAndWhere) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + tableAndWhere, Long.class);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("删除共享封面的其中一首歌：任务先落库，核对后封面 RETAINED、音频 SUCCEEDED，封面文件未被删")
    void keepsSharedCoverWhenOneReferrerIsDeleted() {
        // 包在显式事务里，才能在"提交前"这个确定性时点断言对象存储从未被碰
        // （提交后 afterCommit 会立刻异步唤醒 worker，那时的调用是预期行为）
        tx.execute(status -> {
            songService.deleteSong(10L);
            assertEquals(2, countRows("tb_media_cleanup_task"));
            assertTaskStatus(SHARED_COVER_KEY, MediaCleanupTask.STATUS_PENDING);
            assertTaskStatus("songs/audio-10.mp3", MediaCleanupTask.STATUS_PENDING);
            verify(minio, never()).deleteObject(anyString());
            return null;
        });

        mediaCleanup.runDueTasks();

        // 其余两首歌和专辑还引用这张封面 → RETAINED；音频只有这一首 → SUCCEEDED
        awaitTaskStatus(SHARED_COVER_KEY, MediaCleanupTask.STATUS_RETAINED);
        awaitTaskStatus("songs/audio-10.mp3", MediaCleanupTask.STATUS_SUCCEEDED);
        verify(minio, never()).deleteObject(SHARED_COVER_KEY);
        verify(minio, atLeastOnce()).deleteObject("songs/audio-10.mp3");
    }

    @Test
    @DisplayName("删除最后一条引用：提交后入队，worker 执行后文件真正被删")
    void deletesObjectOnceLastReferenceIsGone() {
        tx.execute(status -> {
            songService.deleteSong(10L);
            songService.deleteSong(11L);
            songService.deleteSong(12L);
            // 连专辑一起删（deleteSongs=true）：SHARED_COVER_KEY 的最后一批引用消失
            albumService.deleteAlbum(9L, true);
            assertEquals(0L, countRows("tb_song"));
            assertEquals(0L, countRows("tb_album"));
            verify(minio, never()).deleteObject(anyString()); // 提交前绝不碰对象存储
            return null;
        });

        mediaCleanup.runDueTasks();

        // 同一对象键的 4 条来源任务（3 首歌 + 专辑）都会尝试删除同一 key，S3 幂等
        verify(minio, atLeastOnce()).deleteObject(SHARED_COVER_KEY);
        verify(minio, atLeastOnce()).deleteObject("songs/audio-10.mp3");
        verify(minio, atLeastOnce()).deleteObject("songs/audio-11.mp3");
        verify(minio, atLeastOnce()).deleteObject("songs/audio-12.mp3");
        awaitTaskStatus(SHARED_COVER_KEY, MediaCleanupTask.STATUS_SUCCEEDED);
        awaitTaskStatus("songs/audio-10.mp3", MediaCleanupTask.STATUS_SUCCEEDED);
    }

    @Test
    @DisplayName("只删专辑、保留歌曲：歌曲仍引用封面，专辑封面任务 RETAINED")
    void keepsCoverWhenAlbumIsDeletedButSongsRemain() {
        albumService.deleteAlbum(9L, false);

        assertEquals(3L, countRows("tb_song"));
        Integer orphaned = jdbc.queryForObject("SELECT COUNT(*) FROM tb_song WHERE album_id IS NULL", Integer.class);
        assertEquals(3, orphaned);

        mediaCleanup.runDueTasks();

        assertEquals(1, countRows("tb_media_cleanup_task"));
        awaitTaskStatus(SHARED_COVER_KEY, MediaCleanupTask.STATUS_RETAINED);
        verify(minio, never()).deleteObject(anyString());
    }

    @Test
    @DisplayName("业务事务回滚：任务表一并回滚，文件从未被碰")
    void rollingBackTheBusinessTransactionAlsoRollsBackTheTask() {
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            songService.deleteSong(10L);
            assertEquals(2, countRows("tb_media_cleanup_task")); // 任务在事务内已可见
            throw new IllegalStateException("模拟业务失败");
        }));

        assertEquals(1L, countRows("tb_song WHERE id = 10"));
        assertEquals(0, countRows("tb_media_cleanup_task"));
        verify(minio, never()).deleteObject(anyString());
    }

    @Test
    @DisplayName("任务写入失败：业务事务一并回滚，不允许出现孤儿删除")
    void failingTaskInsertRollsBackTheBusinessChange() {
        MediaCleanupTaskMapper failingMapper = spy(taskMapper);
        doThrow(new RuntimeException("task table down")).when(failingMapper)
                .enqueue(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any(), any());
        MediaCleanupService broken = new MediaCleanupService(failingMapper,
                new MediaReferenceService(referenceMapper), minio, "vibe-music-data");
        SongServiceImpl brokenSongs = Config.buildSongService(songMapper, broken);
        // 手工 new 的实例没有事务代理，用 TransactionTemplate 显式提供与生产一致的物理事务边界
        assertThrows(RuntimeException.class, () -> tx.execute(status -> {
            brokenSongs.deleteSong(10L);
            return null;
        }));

        assertEquals(1L, countRows("tb_song WHERE id = 10"));
        assertEquals(0, countRows("tb_media_cleanup_task"));
        verify(minio, never()).deleteObject(anyString());
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        private static final JdbcDataSource SOURCE = new JdbcDataSource();

        Config() {
            SOURCE.setURL("jdbc:h2:mem:media_shared_cover;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        }

        static DataSource dataSource() {
            return SOURCE;
        }

        @Bean
        DataSource dataSourceBean() {
            return SOURCE;
        }

        @Bean
        DataSourceTransactionManager transactionManager() {
            return new DataSourceTransactionManager(SOURCE);
        }

        @Bean
        TransactionTemplate transactionTemplate(DataSourceTransactionManager tm) {
            return new TransactionTemplate(tm);
        }

        @Bean
        SqlSessionTemplate session() throws Exception {
            MybatisConfiguration config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            config.addMapper(SongMapper.class);
            config.addMapper(AlbumMapper.class);
            config.addMapper(MediaCleanupTaskMapper.class);
            config.addMapper(MediaReferenceMapper.class);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(SOURCE);
            factory.setConfiguration(config);
            return new SqlSessionTemplate(factory.getObject());
        }

        @Bean
        SongMapper songMapper(SqlSessionTemplate session) {
            return session.getMapper(SongMapper.class);
        }

        @Bean
        AlbumMapper albumMapper(SqlSessionTemplate session) {
            return session.getMapper(AlbumMapper.class);
        }

        @Bean
        MediaCleanupTaskMapper taskMapper(SqlSessionTemplate session) {
            return session.getMapper(MediaCleanupTaskMapper.class);
        }

        @Bean
        MediaReferenceMapper referenceMapper(SqlSessionTemplate session) {
            return session.getMapper(MediaReferenceMapper.class);
        }

        @Bean
        MinioService minio() {
            return mock(MinioService.class);
        }

        @Bean
        cn.edu.seig.vibemusic.mapper.UserFavoriteMapper userFavoriteMapper() {
            return mock(cn.edu.seig.vibemusic.mapper.UserFavoriteMapper.class);
        }

        @Bean
        cn.edu.seig.vibemusic.mapper.StyleMapper styleMapper() {
            return mock(cn.edu.seig.vibemusic.mapper.StyleMapper.class);
        }

        @Bean
        cn.edu.seig.vibemusic.mapper.GenreMapper genreMapper() {
            return mock(cn.edu.seig.vibemusic.mapper.GenreMapper.class);
        }

        @Bean
        cn.edu.seig.vibemusic.mapper.ArtistMapper artistMapper() {
            return mock(cn.edu.seig.vibemusic.mapper.ArtistMapper.class);
        }

        @Bean
        RedisTemplate redisTemplate() {
            return mock(RedisTemplate.class);
        }

        @Bean
        cn.edu.seig.vibemusic.service.SongArtistService songArtistService() {
            return mock(cn.edu.seig.vibemusic.service.SongArtistService.class);
        }

        @Bean
        MediaReferenceService mediaReferenceService(MediaReferenceMapper mapper) {
            return new MediaReferenceService(mapper);
        }

        @Bean
        MediaCleanupService mediaCleanup(MediaCleanupTaskMapper taskMapper, MediaReferenceService referenceService,
                                         MinioService minio) {
            return new MediaCleanupService(taskMapper, referenceService, minio, "vibe-music-data");
        }

        /** 仅供需要替换依赖的用例手工装配；容器里的 service bean 走字段注入。 */
        static SongServiceImpl buildSongService(SongMapper songMapper, MediaCleanupService mediaCleanup) {
            SongServiceImpl service = new SongServiceImpl();
            ReflectionTestUtils.setField(service, "songMapper", songMapper);
            ReflectionTestUtils.setField(service, "albumMapper", mock(AlbumMapper.class));
            ReflectionTestUtils.setField(service, "userFavoriteMapper",
                    mock(cn.edu.seig.vibemusic.mapper.UserFavoriteMapper.class));
            ReflectionTestUtils.setField(service, "styleMapper",
                    mock(cn.edu.seig.vibemusic.mapper.StyleMapper.class));
            ReflectionTestUtils.setField(service, "genreMapper",
                    mock(cn.edu.seig.vibemusic.mapper.GenreMapper.class));
            ReflectionTestUtils.setField(service, "redisTemplate", mock(RedisTemplate.class));
            ReflectionTestUtils.setField(service, "songArtistService",
                    mock(cn.edu.seig.vibemusic.service.SongArtistService.class));
            ReflectionTestUtils.setField(service, "mediaCleanupService", mediaCleanup);
            return service;
        }

        @Bean
        SongServiceImpl songService(SongMapper songMapper, AlbumMapper albumMapper,
                                    MediaCleanupService mediaCleanup) {
            SongServiceImpl service = new SongServiceImpl();
            ReflectionTestUtils.setField(service, "songMapper", songMapper);
            ReflectionTestUtils.setField(service, "albumMapper", albumMapper);
            return service;
        }

        @Bean
        AlbumServiceImpl albumService(AlbumMapper albumMapper, SongMapper songMapper,
                                      MediaCleanupService mediaCleanup) {
            AlbumServiceImpl service = new AlbumServiceImpl();
            ReflectionTestUtils.setField(service, "albumMapper", albumMapper);
            ReflectionTestUtils.setField(service, "songMapper", songMapper);
            return service;
        }
    }
}
