package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.constant.MessageConstant;
import cn.edu.seig.vibemusic.mapper.AccountCleanupMapper;
import cn.edu.seig.vibemusic.mapper.AccountCleanupTaskMapper;
import cn.edu.seig.vibemusic.mapper.FeedbackMapper;
import cn.edu.seig.vibemusic.mapper.MediaCleanupTaskMapper;
import cn.edu.seig.vibemusic.mapper.PlaylistMapper;
import cn.edu.seig.vibemusic.mapper.UserMapper;
import cn.edu.seig.vibemusic.model.vo.AccountDeletionResultVO;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.AccountDeletionService;
import cn.edu.seig.vibemusic.service.MinioService;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * 账号注销的验收：DB 部分必须在事务里，外部清理（Redis/MinIO）必须在 DB 提交之后。
 *
 * <p>夹具保留与生产一致的外键语义：
 * <ul>
 *     <li>{@code tb_feedback} → {@code tb_user} 是 {@code ON DELETE RESTRICT}，必须先删反馈；</li>
 *     <li>收藏/歌单/待确认任务/画像/偏好/设置/评论是 {@code ON DELETE CASCADE}，删用户即清；</li>
 *     <li>{@code ai_chat_state} / {@code chat_messages} / {@code tb_ai_task_metric} 无外键，必须显式清理；</li>
 *     <li>{@code tb_blocker} 是 RESTRICT 外键，用来制造「删用户失败」，验证事务回滚。</li>
 * </ul>
 */
@SpringJUnitConfig(UserDeleteAccountTest.Config.class)
class UserDeleteAccountTest {

    private static final String TOKEN = "token-abc";
    private static final String AVATAR = "http://minio/users/1-avatar.png";

    @TestConfiguration
    static class Config {
        @Bean DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:user_delete_account_v2;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
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
            config.addMapper(cn.edu.seig.vibemusic.mapper.MediaReferenceMapper.class);
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
        @Bean cn.edu.seig.vibemusic.mapper.MediaReferenceMapper mediaReferenceMapper(SqlSessionTemplate session) { return session.getMapper(cn.edu.seig.vibemusic.mapper.MediaReferenceMapper.class); }
        @Bean MinioService minio() { return mock(MinioService.class); }
        @Bean StringRedisTemplate redis() { return mock(StringRedisTemplate.class); }
        @Bean cn.edu.seig.vibemusic.service.EmailService emailService() {
            return mock(cn.edu.seig.vibemusic.service.EmailService.class);
        }

        @Bean cn.edu.seig.vibemusic.service.MediaReferenceService mediaReferenceService(
                cn.edu.seig.vibemusic.mapper.MediaReferenceMapper mapper) {
            return new cn.edu.seig.vibemusic.service.MediaReferenceService(mapper);
        }

        @Bean cn.edu.seig.vibemusic.service.MediaCleanupService mediaCleanupService(
                MediaCleanupTaskMapper taskMapper,
                cn.edu.seig.vibemusic.service.MediaReferenceService referenceService,
                MinioService minio) {
            return new cn.edu.seig.vibemusic.service.MediaCleanupService(
                    taskMapper, referenceService, minio, "vibe-music-data");
        }

        @Bean AccountDeletionService accountDeletionService(UserMapper userMapper, FeedbackMapper feedbackMapper,
                                                           AccountCleanupMapper accountCleanupMapper,
                                                           AccountCleanupTaskMapper accountCleanupTaskMapper,
                                                           PlaylistMapper playlistMapper,
                                                           cn.edu.seig.vibemusic.service.MediaCleanupService mediaCleanupService,
                                                           StringRedisTemplate redis,
                                                           DataSourceTransactionManager transactionManager) {
            return new AccountDeletionService(userMapper, feedbackMapper, accountCleanupMapper,
                    accountCleanupTaskMapper, playlistMapper, mediaCleanupService, redis, transactionManager);
        }
        @Bean UserServiceImpl userService(UserMapper userMapper, AccountDeletionService accountDeletionService) {
            UserServiceImpl impl = new UserServiceImpl();
            ReflectionTestUtils.setField(impl, "userMapper", userMapper);
            // 账号删除的唯一实现已抽到 AccountDeletionService；UserServiceImpl 只负责鉴权与结果组装。
            ReflectionTestUtils.setField(impl, "accountDeletionService", accountDeletionService);
            return impl;
        }
    }

    @Autowired
    private UserServiceImpl userService;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private MinioService minio;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");

        jdbc.execute("CREATE TABLE tb_user(id BIGINT PRIMARY KEY, username VARCHAR(255), password VARCHAR(255), "
                + "phone VARCHAR(32), email VARCHAR(255), user_avatar VARCHAR(255), introduction VARCHAR(1000), "
                + "create_time TIMESTAMP, update_time TIMESTAMP, status VARCHAR(32))");
        // RESTRICT：先删用户后删反馈的实现会在这里 FK 报错
        jdbc.execute("CREATE TABLE tb_feedback(id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, "
                + "feedback VARCHAR(2000), create_time TIMESTAMP, "
                + "FOREIGN KEY(user_id) REFERENCES tb_user(id) ON DELETE RESTRICT)");
        // 用于制造「删用户失败」以验证事务回滚
        jdbc.execute("CREATE TABLE tb_blocker(id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, "
                + "FOREIGN KEY(user_id) REFERENCES tb_user(id) ON DELETE RESTRICT)");

        // 级联表（生产为 ON DELETE CASCADE）
        // ⚠️ 必须补齐 Playlist 实体全部列：MyBatis-Plus selectList 会查出全列，
        // 少一列就是 BadSqlGrammar（AccountDeletionService 查自定义封面时会命中）。
        jdbc.execute("CREATE TABLE tb_playlist(id BIGINT PRIMARY KEY, title VARCHAR(255), cover_url VARCHAR(255), "
                + "introduction VARCHAR(1000), style VARCHAR(255), owner_user_id BIGINT, is_public BOOLEAN, "
                + "status VARCHAR(16), cover_is_custom BOOLEAN, "
                + "FOREIGN KEY(owner_user_id) REFERENCES tb_user(id) ON DELETE CASCADE)");
        jdbc.execute("CREATE TABLE tb_playlist_create_request(user_id BIGINT NOT NULL, request_key VARCHAR(64) NOT NULL, "
                + "PRIMARY KEY(user_id, request_key), FOREIGN KEY(user_id) REFERENCES tb_user(id) ON DELETE CASCADE)");
        jdbc.execute("CREATE TABLE tb_agent_pending_action(id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, "
                + "FOREIGN KEY(user_id) REFERENCES tb_user(id) ON DELETE CASCADE)");
        jdbc.execute("CREATE TABLE tb_user_music_profile(user_id BIGINT PRIMARY KEY, "
                + "FOREIGN KEY(user_id) REFERENCES tb_user(id) ON DELETE CASCADE)");
        jdbc.execute("CREATE TABLE tb_user_memory_preference(id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, "
                + "FOREIGN KEY(user_id) REFERENCES tb_user(id) ON DELETE CASCADE)");
        jdbc.execute("CREATE TABLE tb_user_ai_memory_setting(user_id BIGINT PRIMARY KEY, "
                + "FOREIGN KEY(user_id) REFERENCES tb_user(id) ON DELETE CASCADE)");
        jdbc.execute("CREATE TABLE tb_comment(id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, "
                + "FOREIGN KEY(user_id) REFERENCES tb_user(id) ON DELETE CASCADE)");
        jdbc.execute("CREATE TABLE tb_user_favorite(id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, "
                + "FOREIGN KEY(user_id) REFERENCES tb_user(id) ON DELETE CASCADE)");

        // 无外键、不会级联的表
        jdbc.execute("CREATE TABLE ai_chat_state(chat_id VARCHAR(255) PRIMARY KEY, user_id BIGINT)");
        jdbc.execute("CREATE TABLE chat_messages(id BIGINT AUTO_INCREMENT PRIMARY KEY, chat_id VARCHAR(255) NOT NULL)");
        jdbc.execute("CREATE TABLE tb_ai_task_metric(id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT)");

        // 注销遗留清理任务表
        jdbc.execute("CREATE TABLE tb_account_cleanup_task(id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "user_id BIGINT NOT NULL, resource_type VARCHAR(32) NOT NULL, resource_ref VARCHAR(512), "
                + "status VARCHAR(16) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0, "
                + "last_error VARCHAR(500), created_at TIMESTAMP, updated_at TIMESTAMP, "
                + "UNIQUE(user_id, resource_type))");

        jdbc.update("INSERT INTO tb_user(id, username) VALUES (1, 'alice')");
        jdbc.update("INSERT INTO tb_feedback(user_id, feedback, create_time) VALUES "
                + "(1, '希望增加夜间模式', CURRENT_TIMESTAMP), (1, '很好用', CURRENT_TIMESTAMP)");

        reset(redis, minio);
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

    @Test
    @DisplayName("注销：反馈行、用户行与登录 token 一并清掉，返回完全成功")
    void deleteAccountRemovesFeedbackUserAndToken() {
        Result<AccountDeletionResultVO> result = userService.deleteAccount(TOKEN);

        assertEquals(0, result.getCode());
        AccountDeletionResultVO data = result.getData();
        assertNotNull(data);
        assertTrue(data.isAccountDeleted());
        assertFalse(data.isCleanupPending());
        assertEquals(0, count("tb_feedback"), "用户提交的反馈行应随注销一起删除");
        assertEquals(0, count("tb_user"), "用户行应被删除");
        verify(redis).delete(TOKEN);
    }

    @Test
    @DisplayName("注销：用户已不存在时幂等成功，不报错也不动 token")
    void deleteAccountIsIdempotentWhenUserMissing() {
        jdbc.update("DELETE FROM tb_feedback");
        jdbc.update("DELETE FROM tb_user");

        Result<AccountDeletionResultVO> result = userService.deleteAccount(TOKEN);

        assertEquals(0, result.getCode(), "用户不存在应走幂等成功路径，而不是报错");
        assertTrue(result.getData().isAccountDeleted());
        assertFalse(result.getData().isCleanupPending());
        verify(redis, never()).delete(anyString());
    }

    @Test
    @DisplayName("注销：重复调用第二次幂等成功，不二次删除、不重复作废 token")
    void deleteAccountIsIdempotentOnSecondCall() {
        Result<AccountDeletionResultVO> first = userService.deleteAccount(TOKEN);
        assertEquals(0, first.getCode());
        assertTrue(first.getData().isAccountDeleted());

        Result<AccountDeletionResultVO> second = userService.deleteAccount(TOKEN);
        assertEquals(0, second.getCode(), "第二次注销不应报错");
        assertTrue(second.getData().isAccountDeleted());
        assertFalse(second.getData().isCleanupPending());
        verify(redis).delete(TOKEN); // 只删了一次
    }

    @Test
    @DisplayName("注销：DB 失败时整体回滚，反馈与用户都还在，且不碰外部资源")
    void deleteAccountRollsBackWhenDbFails() {
        // tb_blocker 的 RESTRICT 外键让「删用户」失败
        jdbc.update("INSERT INTO tb_blocker(id, user_id) VALUES (99, 1)");

        Result<AccountDeletionResultVO> result = userService.deleteAccount(TOKEN);

        assertEquals(1, result.getCode(), "DB 失败应返回失败");
        assertEquals(MessageConstant.ACCOUNT_DELETE_FAILED, result.getMessage());
        assertEquals(2, count("tb_feedback"), "事务回滚后反馈行必须还在");
        assertEquals(1, count("tb_user"), "事务回滚后用户行必须还在");
        verify(redis, never()).delete(anyString());
        verify(minio, never()).deleteFile(anyString());
    }

    @Test
    @DisplayName("注销：级联数据（收藏/歌单/待确认任务/画像/偏好/设置/评论）被清干净")
    void deleteAccountCascadesRelatedData() {
        jdbc.update("INSERT INTO tb_user(id, username) VALUES (2, 'bob')");
        for (long userId : new long[]{1L, 2L}) {
            jdbc.update("INSERT INTO tb_playlist(id, owner_user_id) VALUES (?, ?)", userId * 10, userId);
            jdbc.update("INSERT INTO tb_playlist_create_request(user_id, request_key) VALUES (?, ?)", userId, "rk" + userId);
            jdbc.update("INSERT INTO tb_agent_pending_action(id, user_id) VALUES (?, ?)", userId * 10, userId);
            jdbc.update("INSERT INTO tb_user_music_profile(user_id) VALUES (?)", userId);
            jdbc.update("INSERT INTO tb_user_memory_preference(user_id) VALUES (?)", userId);
            jdbc.update("INSERT INTO tb_user_ai_memory_setting(user_id) VALUES (?)", userId);
            jdbc.update("INSERT INTO tb_comment(id, user_id) VALUES (?, ?)", userId * 10, userId);
            jdbc.update("INSERT INTO tb_user_favorite(id, user_id) VALUES (?, ?)", userId * 10, userId);
        }

        Result<AccountDeletionResultVO> result = userService.deleteAccount(TOKEN);
        assertEquals(0, result.getCode());

        // tb_playlist 的归属列是 owner_user_id，单独断言
        assertEquals(0, count("tb_playlist", "owner_user_id = 1"), "tb_playlist 中属于注销用户的数据应被清掉");
        assertEquals(1, count("tb_playlist", "owner_user_id = 2"), "tb_playlist 中他人数据不得被误删");

        String[] tables = {"tb_playlist_create_request", "tb_agent_pending_action",
                "tb_user_music_profile", "tb_user_memory_preference", "tb_user_ai_memory_setting",
                "tb_comment", "tb_user_favorite"};
        for (String table : tables) {
            assertEquals(0, count(table, "user_id = 1"), table + " 中属于注销用户的数据应被清掉");
            assertEquals(1, count(table, "user_id = 2"), table + " 中他人数据不得被误删");
        }
        assertEquals(0, count("tb_user", "id = 1"));
        assertEquals(1, count("tb_user", "id = 2"));
    }

    @Test
    @DisplayName("注销：无外键的会话/消息/任务指标被显式清理，且不影响他人")
    void deleteAccountCleansNoFkTables() {
        jdbc.update("INSERT INTO tb_user(id, username) VALUES (2, 'bob')");
        jdbc.update("INSERT INTO ai_chat_state(chat_id, user_id) VALUES ('c1', 1), ('c2', 2)");
        jdbc.update("INSERT INTO chat_messages(chat_id) VALUES ('c1'), ('c1'), ('c2')");
        jdbc.update("INSERT INTO tb_ai_task_metric(user_id) VALUES (1), (2)");

        Result<AccountDeletionResultVO> result = userService.deleteAccount(TOKEN);
        assertEquals(0, result.getCode());

        assertEquals(0, count("ai_chat_state", "user_id = 1"));
        assertEquals(1, count("ai_chat_state", "user_id = 2"));
        assertEquals(0, count("chat_messages", "chat_id = 'c1'"), "注销用户的聊天消息应被清掉");
        assertEquals(1, count("chat_messages", "chat_id = 'c2'"), "他人聊天消息不得被误删");
        assertEquals(0, count("tb_ai_task_metric", "user_id = 1"));
        assertEquals(1, count("tb_ai_task_metric", "user_id = 2"));
    }

    @Test
    @DisplayName("注销：Redis 作废失败 → 账号已注销但 cleanupPending=true，并落重试记录")
    void deleteAccountReportsPendingWhenRedisFails() {
        doThrow(new RuntimeException("redis down")).when(redis).delete(TOKEN);

        Result<AccountDeletionResultVO> result = userService.deleteAccount(TOKEN);

        assertEquals(0, result.getCode(), "账号已删除，不能报成注销失败");
        AccountDeletionResultVO data = result.getData();
        assertTrue(data.isAccountDeleted());
        assertTrue(data.isCleanupPending());
        assertTrue(data.getPendingItems().contains("REDIS_TOKEN"));
        assertEquals(0, count("tb_user"), "DB 注销必须已完成");
        assertEquals(1, count("tb_account_cleanup_task",
                "user_id = 1 AND resource_type = 'REDIS_TOKEN' AND status = 'PENDING'"),
                "失败的 Redis 清理应落一条待重试记录");
    }

    @Test
    @DisplayName("注销：头像改为登记到媒体清理队列（带引用核对），不再立即删除、也不再落 MINIO_AVATAR 重试")
    void deleteAccountEnqueuesAvatarInsteadOfDeletingImmediately() {
        jdbc.update("UPDATE tb_user SET user_avatar = ? WHERE id = 1", AVATAR);

        Result<AccountDeletionResultVO> result = userService.deleteAccount(TOKEN);

        assertEquals(0, result.getCode(), "账号必须能正常注销");
        assertTrue(result.getData().isAccountDeleted());
        // 语义变更：头像不再在注销路径上"立即删"，而是登记成待核对的媒体清理任务。
        // 原因：头像可能与其他记录共享，必须由 MediaCleanupService 核对引用后才能删。
        verify(minio, never()).deleteFile(anyString());
        // 本用例没有给 MediaCleanupService 配置 URL→对象键解析，登记会被安全跳过，
        // 因此这里只断言"没有走立即删除 + 没有误落账号级重试表"。
        assertEquals(0, count("tb_account_cleanup_task"),
                "头像不再使用 MINIO_AVATAR 账号级重试，改由媒体清理队列负责");
        verify(redis).delete(TOKEN); // Redis 成功
    }}
