package cn.edu.seig.vibemusic.service;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.constant.MediaCleanupSourceType;
import cn.edu.seig.vibemusic.constant.MessageConstant;
import cn.edu.seig.vibemusic.mapper.AccountCleanupMapper;
import cn.edu.seig.vibemusic.mapper.AccountCleanupTaskMapper;
import cn.edu.seig.vibemusic.mapper.FeedbackMapper;
import cn.edu.seig.vibemusic.mapper.MediaCleanupTaskMapper;
import cn.edu.seig.vibemusic.mapper.MediaReferenceMapper;
import cn.edu.seig.vibemusic.mapper.PlaylistMapper;
import cn.edu.seig.vibemusic.mapper.UserMapper;
import cn.edu.seig.vibemusic.model.vo.AccountDeletionResultVO;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.AccountDeletionService.AccountDeletionOutcome;
import cn.edu.seig.vibemusic.service.AccountDeletionService.BatchDeletionOutcome;
import cn.edu.seig.vibemusic.service.impl.UserServiceImpl;
import cn.edu.seig.vibemusic.security.AuthSessionService;
import cn.edu.seig.vibemusic.security.ImageStorageService;
import cn.edu.seig.vibemusic.security.PasswordService;
import cn.edu.seig.vibemusic.security.VerificationCodeService;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * 统一账号删除服务（D 阶段）的数据库级验收。
 *
 * <p>这里刻意用「真实 H2（MySQL 兼容模式）+ 真实 mapper SQL + 真实外键语义」，
 * 而不是 mock 业务层 —— 要验证的正是这些只有数据库能证明的事实：
 * <ul>
 *     <li>{@code tb_feedback} 的 {@code ON DELETE RESTRICT} 会不会挡住删除；</li>
 *     <li>{@code tb_playlist.owner_user_id} 的 {@code ON DELETE CASCADE} 发生后，
 *         封面是否<b>在删除之前</b>就被登记进清理队列（晚一步就再也查不到）；</li>
 *     <li>批量删除是否真的整体回滚，而不是"前几个删了、后面失败"；</li>
 *     <li>管理员删除与自助注销是否产生完全相同的数据库结果。</li>
 * </ul>
 */
@SpringJUnitConfig(AccountDeletionServiceTest.Config.class)
class AccountDeletionServiceTest {

    private static final String TOKEN = "token-alice";
    private static final String AVATAR = "http://localhost:9005/vibe-music-data/avatars/alice.png";

    @TestConfiguration
    static class Config {
        @Bean DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:account_deletion_d;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
            return source;
        }

        @Bean DataSourceTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean SqlSessionTemplate session(DataSource source) throws Exception {
            MybatisConfiguration config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            config.addMapper(UserMapper.class);
            config.addMapper(FeedbackMapper.class);
            config.addMapper(AccountCleanupMapper.class);
            config.addMapper(AccountCleanupTaskMapper.class);
            config.addMapper(PlaylistMapper.class);
            config.addMapper(MediaCleanupTaskMapper.class);
            config.addMapper(MediaReferenceMapper.class);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(config);
            MybatisPlusInterceptor pagination = new MybatisPlusInterceptor();
            pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.H2));
            factory.setPlugins(pagination);
            return new SqlSessionTemplate(factory.getObject());
        }

        @Bean UserMapper userMapper(SqlSessionTemplate session) { return session.getMapper(UserMapper.class); }
        @Bean FeedbackMapper feedbackMapper(SqlSessionTemplate session) { return session.getMapper(FeedbackMapper.class); }
        @Bean AccountCleanupMapper accountCleanupMapper(SqlSessionTemplate session) { return session.getMapper(AccountCleanupMapper.class); }
        @Bean AccountCleanupTaskMapper accountCleanupTaskMapper(SqlSessionTemplate session) { return session.getMapper(AccountCleanupTaskMapper.class); }
        @Bean PlaylistMapper playlistMapper(SqlSessionTemplate session) { return session.getMapper(PlaylistMapper.class); }
        @Bean MediaCleanupTaskMapper mediaCleanupTaskMapper(SqlSessionTemplate session) { return session.getMapper(MediaCleanupTaskMapper.class); }
        @Bean MediaReferenceMapper mediaReferenceMapper(SqlSessionTemplate session) { return session.getMapper(MediaReferenceMapper.class); }
        @Bean MinioService minio() { return mock(MinioService.class); }
        @Bean StringRedisTemplate redis() { return mock(StringRedisTemplate.class); }
        @Bean cn.edu.seig.vibemusic.service.EmailService emailService() {
            return mock(cn.edu.seig.vibemusic.service.EmailService.class);
        }

        @Bean MediaReferenceService mediaReferenceService(MediaReferenceMapper mapper) {
            return new MediaReferenceService(mapper);
        }

        @Bean MediaCleanupService mediaCleanupService(MediaCleanupTaskMapper taskMapper,
                                                      MediaReferenceService referenceService,
                                                      MinioService minioService) {
            return new MediaCleanupService(taskMapper, referenceService, minioService, "vibe-music-data");
        }

        @Bean AccountDeletionService accountDeletionService(UserMapper userMapper,
                                                            FeedbackMapper feedbackMapper,
                                                            AccountCleanupMapper accountCleanupMapper,
                                                            AccountCleanupTaskMapper accountCleanupTaskMapper,
                                                            PlaylistMapper playlistMapper,
                                                            MediaCleanupService mediaCleanupService,
                                                            AuthSessionService authSessionService,
                                                            DataSourceTransactionManager transactionManager) {
            return new AccountDeletionService(userMapper, feedbackMapper, accountCleanupMapper,
                    accountCleanupTaskMapper, playlistMapper, mediaCleanupService, authSessionService,
                    transactionManager);
        }

        @Bean AuthSessionService authSessionService() { return mock(AuthSessionService.class); }
        @Bean PasswordService passwordService() { return new PasswordService(); }
        @Bean VerificationCodeService verificationCodeService() { return mock(VerificationCodeService.class); }
        @Bean ImageStorageService imageStorageService() { return mock(ImageStorageService.class); }

        @Bean UserServiceImpl userService(UserMapper userMapper, AccountDeletionService accountDeletionService,
                                          AuthSessionService authSessionService,
                                          PasswordService passwordService,
                                          VerificationCodeService verificationCodeService,
                                          ImageStorageService imageStorageService) {
            UserServiceImpl impl = new UserServiceImpl();
            ReflectionTestUtils.setField(impl, "userMapper", userMapper);
            ReflectionTestUtils.setField(impl, "accountDeletionService", accountDeletionService);
            ReflectionTestUtils.setField(impl, "authSessionService", authSessionService);
            ReflectionTestUtils.setField(impl, "passwordService", passwordService);
            ReflectionTestUtils.setField(impl, "verificationCodeService", verificationCodeService);
            ReflectionTestUtils.setField(impl, "imageStorageService", imageStorageService);
            return impl;
        }
    }

    @Autowired private AccountDeletionService deletionService;
    @Autowired private UserServiceImpl userService;
    @Autowired private AuthSessionService authSessionService;
    @Autowired private MinioService minio;
    @Autowired private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");

        jdbc.execute("CREATE TABLE tb_user(id BIGINT PRIMARY KEY, username VARCHAR(255), password VARCHAR(255), "
                + "phone VARCHAR(32), email VARCHAR(255), user_avatar VARCHAR(255), introduction VARCHAR(1000), "
                + "create_time TIMESTAMP, update_time TIMESTAMP, status VARCHAR(32), "
                + "auth_version BIGINT NOT NULL DEFAULT 0)");
        // RESTRICT：不先删反馈就删用户，会在这里外键报错（历史 bug 的复现点）
        jdbc.execute("CREATE TABLE tb_feedback(id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, "
                + "feedback VARCHAR(2000), create_time TIMESTAMP, "
                + "FOREIGN KEY(user_id) REFERENCES tb_user(id) ON DELETE RESTRICT)");
        jdbc.execute("CREATE TABLE tb_blocker(id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, "
                + "FOREIGN KEY(user_id) REFERENCES tb_user(id) ON DELETE RESTRICT)");
        // CASCADE：删用户后歌单行消失，所以封面必须在删除之前登记。
        // ⚠️ 必须补齐 Playlist 实体的**全部**列：MyBatis-Plus 的 selectList 会查出全列，
        // 少一列就是 BadSqlGrammar（项目历史踩过的坑）。
        jdbc.execute("CREATE TABLE tb_playlist(id BIGINT AUTO_INCREMENT PRIMARY KEY, title VARCHAR(255), "
                + "cover_url VARCHAR(255), introduction VARCHAR(1000), style VARCHAR(255), "
                + "owner_user_id BIGINT, is_public BOOLEAN, status VARCHAR(16), cover_is_custom BOOLEAN, "
                + "FOREIGN KEY(owner_user_id) REFERENCES tb_user(id) ON DELETE CASCADE)");

        // 无外键表
        jdbc.execute("CREATE TABLE ai_chat_state(chat_id VARCHAR(255) PRIMARY KEY, user_id BIGINT)");
        jdbc.execute("CREATE TABLE chat_messages(id BIGINT AUTO_INCREMENT PRIMARY KEY, chat_id VARCHAR(255) NOT NULL)");
        jdbc.execute("CREATE TABLE tb_ai_task_metric(id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT)");

        // 账号级重试表（resource_ref 参与唯一键，phaseD 之后的形态）
        jdbc.execute("CREATE TABLE tb_account_cleanup_task(id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "user_id BIGINT NOT NULL, resource_type VARCHAR(32) NOT NULL, resource_ref VARCHAR(512), "
                + "status VARCHAR(16) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0, "
                + "last_error VARCHAR(500), created_at TIMESTAMP, updated_at TIMESTAMP, "
                + "UNIQUE(user_id, resource_type, resource_ref))");

        // 媒体清理任务表（封面入队的落点）
        jdbc.execute("CREATE TABLE tb_media_cleanup_task(id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "bucket VARCHAR(64) NOT NULL, object_key VARCHAR(255) NOT NULL, key_digest CHAR(64) NOT NULL, "
                + "source_type VARCHAR(32) NOT NULL, source_ref VARCHAR(64), reason VARCHAR(64), "
                + "status VARCHAR(16) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0, "
                + "next_run_at TIMESTAMP, lease_owner VARCHAR(64), lease_expires_at TIMESTAMP, "
                + "generation BIGINT NOT NULL DEFAULT 0, last_error VARCHAR(500), "
                + "created_at TIMESTAMP, updated_at TIMESTAMP, "
                + "UNIQUE(source_type, source_ref, key_digest))");

        // 引用核对要查的 7 张表（本用例只关心 playlist 与 user，其余建空表满足 SQL）
        jdbc.execute("CREATE TABLE tb_song(id BIGINT PRIMARY KEY, cover_url VARCHAR(255), audio_url VARCHAR(255))");
        jdbc.execute("CREATE TABLE tb_album(id BIGINT PRIMARY KEY, cover_url VARCHAR(255))");
        jdbc.execute("CREATE TABLE tb_artist(id BIGINT PRIMARY KEY, avatar VARCHAR(255))");
        jdbc.execute("CREATE TABLE tb_banner(id BIGINT PRIMARY KEY, banner_url VARCHAR(255))");

        jdbc.update("INSERT INTO tb_user(id, username, user_avatar) VALUES (1, 'alice', ?)", AVATAR);
        jdbc.update("INSERT INTO tb_feedback(user_id, feedback, create_time) VALUES "
                + "(1, '希望增加夜间模式', CURRENT_TIMESTAMP), (1, '很好用', CURRENT_TIMESTAMP)");

        reset(authSessionService, minio);
        // MinioService 被 mock 后 toObjectName 默认返回 null，MediaCleanupService 会据此认为
        // "没有可删的对象"而直接跳过登记。这里给一个与生产语义一致的 URL→对象键实现。
        org.mockito.Mockito.when(minio.toObjectName(anyString())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            if (url == null || url.isBlank()) {
                return null;
            }
            int marker = url.indexOf("/vibe-music-data/");
            if (marker < 0) {
                return null;
            }
            return url.substring(marker + "/vibe-music-data/".length());
        });
        ThreadLocalUtil.set(Map.of(JwtClaimsConstant.USER_ID, 1L, JwtClaimsConstant.ROLE, "ROLE_USER"));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalUtil.remove();
    }

    private int count(String table) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private int count(String table, String where) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class);
        return value == null ? 0 : value;
    }

    // ------------------------------------------------------------------ 管理员路径

    @Test
    @DisplayName("管理员单删：有反馈的账号也能删掉（先删反馈再删用户，不再被 RESTRICT 拒绝）")
    void adminDeleteRemovesUserThatHasFeedback() {
        Result<?> result = userService.deleteUser(1L);

        assertEquals(0, result.getCode(), "有反馈的账号必须能删掉，不能被 FK RESTRICT 挡住");
        assertEquals(0, count("tb_feedback"), "反馈行应随账号删除一起清理");
        assertEquals(0, count("tb_user"));
    }

    @Test
    @DisplayName("管理员单删：头像与个人歌单自定义封面都被登记进媒体清理队列")
    void adminDeleteEnqueuesAvatarAndCustomCovers() {
        jdbc.update("INSERT INTO tb_playlist(id, title, cover_url, owner_user_id, cover_is_custom) VALUES "
                + "(10, '夜跑', 'http://localhost:9005/vibe-music-data/playlistCovers/a.png', 1, TRUE)");
        jdbc.update("INSERT INTO tb_playlist(id, title, cover_url, owner_user_id, cover_is_custom) VALUES "
                + "(11, '学习', 'http://localhost:9005/vibe-music-data/playlistCovers/b.png', 1, TRUE)");
        // 默认封面（非自定义）不该入队：它是歌曲封面的共享图，由引用核对决定
        jdbc.update("INSERT INTO tb_playlist(id, title, cover_url, owner_user_id, cover_is_custom) VALUES "
                + "(12, '默认封面', 'http://localhost:9005/vibe-music-data/songCovers/shared.png', 1, FALSE)");

        userService.deleteUser(1L);

        // 两个自定义封面各占一行：证明唯一键没有把它们互相覆盖
        assertEquals(2, count("tb_media_cleanup_task",
                        "source_type = 'ACCOUNT_DELETE' AND source_ref = '1'"),
                "两个自定义封面应分别登记，不能因为唯一键冲突只剩一条");
        assertEquals(1, count("tb_media_cleanup_task",
                        "object_key = 'playlistCovers/a.png'"));
        assertEquals(1, count("tb_media_cleanup_task",
                        "object_key = 'playlistCovers/b.png'"));
        assertEquals(0, count("tb_media_cleanup_task",
                        "object_key = 'songCovers/shared.png'"),
                "默认封面（cover_is_custom=false）不应入队");
        // 歌单行本身随 CASCADE 消失
        assertEquals(0, count("tb_playlist"));
    }

    @Test
    @DisplayName("管理员单删：无外键的会话/消息/任务指标被显式清理，且不误伤他人")
    void adminDeleteCleansNoFkTables() {
        jdbc.update("INSERT INTO tb_user(id, username) VALUES (2, 'bob')");
        jdbc.update("INSERT INTO ai_chat_state(chat_id, user_id) VALUES ('c1', 1), ('c2', 2)");
        jdbc.update("INSERT INTO chat_messages(chat_id) VALUES ('c1'), ('c1'), ('c2')");
        jdbc.update("INSERT INTO tb_ai_task_metric(user_id) VALUES (1), (2)");

        userService.deleteUser(1L);

        assertEquals(0, count("ai_chat_state", "user_id = 1"));
        assertEquals(1, count("ai_chat_state", "user_id = 2"));
        assertEquals(0, count("chat_messages", "chat_id = 'c1'"));
        assertEquals(1, count("chat_messages", "chat_id = 'c2'"));
        assertEquals(0, count("tb_ai_task_metric", "user_id = 1"));
        assertEquals(1, count("tb_ai_task_metric", "user_id = 2"));
    }

    @Test
    @DisplayName("管理员单删：用户不存在时返回失败而不是假装成功")
    void adminDeleteOfMissingUserReportsFailure() {
        Result<?> result = userService.deleteUser(999L);
        assertEquals(1, result.getCode());
        assertEquals(MessageConstant.DELETE + MessageConstant.FAILED, result.getMessage());
    }

    // ------------------------------------------------------------------ 批量删除

    @Test
    @DisplayName("批量删除：任一账号失败则整批回滚，不会出现部分删除的中间态")
    void batchDeleteRollsBackEverythingWhenOneUserFails() {
        jdbc.update("INSERT INTO tb_user(id, username) VALUES (2, 'bob'), (3, 'carol')");
        // carol 被 blocker 的 RESTRICT 外键卡住 → 删除必然失败
        jdbc.update("INSERT INTO tb_blocker(id, user_id) VALUES (99, 3)");

        Result<?> result = userService.deleteUsers(List.of(1L, 2L, 3L));

        assertEquals(1, result.getCode(), "部分失败应整体报失败");
        assertEquals(3, count("tb_user"), "整批回滚后三个用户都必须在");
        assertEquals(2, count("tb_feedback"), "回滚后 alice 的反馈也必须在");
        assertEquals(0, count("tb_media_cleanup_task"), "回滚后不应留下任何清理任务");
    }

    @Test
    @DisplayName("批量删除：全部成功时连同反馈一起删干净")
    void batchDeleteRemovesAllUsers() {
        jdbc.update("INSERT INTO tb_user(id, username) VALUES (2, 'bob')");
        jdbc.update("INSERT INTO tb_feedback(user_id, feedback, create_time) VALUES "
                + "(2, '不错', CURRENT_TIMESTAMP)");

        Result<?> result = userService.deleteUsers(List.of(1L, 2L));

        assertEquals(0, result.getCode());
        assertEquals(0, count("tb_user"));
        assertEquals(0, count("tb_feedback"));
    }

    // ------------------------------------------------------------------ 自助注销一致性

    @Test
    @DisplayName("自助注销与管理员删除产生相同的数据库结果（同一套清理顺序）")
    void selfDeletionMatchesAdminDeletion() {
        jdbc.update("INSERT INTO tb_playlist(id, title, cover_url, owner_user_id, cover_is_custom) VALUES "
                + "(10, '夜跑', 'http://localhost:9005/vibe-music-data/playlistCovers/a.png', 1, TRUE)");
        jdbc.update("INSERT INTO ai_chat_state(chat_id, user_id) VALUES ('c1', 1)");

        Result<AccountDeletionResultVO> result = userService.deleteAccount(TOKEN);

        assertEquals(0, result.getCode());
        assertTrue(result.getData().isAccountDeleted());
        assertFalse(result.getData().isCleanupPending());
        assertEquals(0, count("tb_user"));
        assertEquals(0, count("tb_feedback"));
        assertEquals(0, count("ai_chat_state", "user_id = 1"));
        // 与管理员路径一致：封面同样入队
        assertEquals(1, count("tb_media_cleanup_task", "source_type = 'ACCOUNT_DELETE'"));
        verify(authSessionService).revokeUserSession(1L, TOKEN);
    }

    @Test
    @DisplayName("自助注销：用户已不存在时幂等成功，不碰 token")
    void selfDeletionIsIdempotentWhenUserMissing() {
        userService.deleteAccount(TOKEN);
        reset(authSessionService);

        Result<AccountDeletionResultVO> second = userService.deleteAccount(TOKEN);

        assertEquals(0, second.getCode());
        assertTrue(second.getData().isAccountDeleted());
        assertFalse(second.getData().isCleanupPending());
        verify(authSessionService, org.mockito.Mockito.never()).revokeUserSession(anyLong(), anyString());
    }

    @Test
    @DisplayName("自助注销：Redis 撤销失败不回滚账号，也不持久化令牌或摘要")
    void selfDeletionReportsPendingWhenTokenRevokeFails() {
        doThrow(new RuntimeException("redis down")).when(authSessionService).revokeUserSession(1L, TOKEN);

        Result<AccountDeletionResultVO> result = userService.deleteAccount(TOKEN);

        assertEquals(0, result.getCode(), "账号已删除，不能报成注销失败");
        AccountDeletionResultVO data = result.getData();
        assertTrue(data.isAccountDeleted());
        assertFalse(data.isCleanupPending());
        assertEquals(0, count("tb_user"));
        assertEquals(0, count("tb_account_cleanup_task"));
    }

    @Test
    @DisplayName("统一服务：账号不存在时返回 existed=false，调用方据此走幂等")
    void deleteAccountOutcomeReportsMissingUser() {
        AccountDeletionOutcome outcome = deletionService.deleteAccount(999L, null, true);
        assertFalse(outcome.existed());
        assertFalse(outcome.deleted());
    }

    @Test
    @DisplayName("批量删除结果：空入参直接成功且不做事")
    void batchDeleteEmptyInput() {
        BatchDeletionOutcome outcome = deletionService.deleteAccounts(List.of());
        assertTrue(outcome.success());
        assertNotNull(outcome.deletedUserIds());
        assertEquals(0, outcome.deletedUserIds().size());
        assertEquals(1, count("tb_user"));
    }

    @Test
    @DisplayName("封面清理走媒体任务表，来源类型为 ACCOUNT_DELETE（可被引用核对保护）")
    void coversUseMediaCleanupPipeline() {
        jdbc.update("INSERT INTO tb_playlist(id, title, cover_url, owner_user_id, cover_is_custom) VALUES "
                + "(10, '夜跑', 'http://localhost:9005/vibe-music-data/playlistCovers/a.png', 1, TRUE)");

        userService.deleteUser(1L);

        assertEquals(1, count("tb_media_cleanup_task",
                "source_type = '" + MediaCleanupSourceType.ACCOUNT_DELETE + "' AND status = 'PENDING'"),
                "封面应登记为待核对的媒体清理任务，而不是直接删除");
    }
}
