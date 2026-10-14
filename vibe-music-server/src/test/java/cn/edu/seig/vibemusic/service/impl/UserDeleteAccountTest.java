package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.mapper.FeedbackMapper;
import cn.edu.seig.vibemusic.mapper.UserMapper;
import cn.edu.seig.vibemusic.result.Result;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 账号注销的验收：注销必须同时清掉用户提交的反馈行、用户本身，并作废登录 token。
 *
 * <p>tb_feedback 的外键是 ON DELETE RESTRICT：不先删反馈，删用户会直接 FK 报错 ——
 * 所以 H2 夹具里保留了与生产一致的外键约束，钉住「先删反馈再删用户」这个顺序。
 * token 作废用 mock 的 StringRedisTemplate 验证调用。
 */
@SpringJUnitConfig(UserDeleteAccountTest.Config.class)
class UserDeleteAccountTest {

    @TestConfiguration
    static class Config {
        @Bean DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:user_delete_account;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
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
        @Bean MinioService minio() { return mock(MinioService.class); }
        @Bean StringRedisTemplate redis() { return mock(StringRedisTemplate.class); }
        @Bean cn.edu.seig.vibemusic.service.EmailService emailService() {
            return mock(cn.edu.seig.vibemusic.service.EmailService.class);
        }

        @Bean UserServiceImpl userService(UserMapper userMapper, FeedbackMapper feedbackMapper,
                                          StringRedisTemplate redis, MinioService minio) {
            UserServiceImpl impl = new UserServiceImpl();
            ReflectionTestUtils.setField(impl, "userMapper", userMapper);
            ReflectionTestUtils.setField(impl, "feedbackMapper", feedbackMapper);
            ReflectionTestUtils.setField(impl, "stringRedisTemplate", redis);
            ReflectionTestUtils.setField(impl, "minioService", minio);
            return impl;
        }
    }

    @Autowired
    private UserServiceImpl userService;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS tb_feedback");
        jdbc.execute("DROP TABLE IF EXISTS tb_user");
        jdbc.execute("CREATE TABLE tb_user(id BIGINT PRIMARY KEY, username VARCHAR(255), password VARCHAR(255), "
                + "phone VARCHAR(32), email VARCHAR(255), user_avatar VARCHAR(255), introduction VARCHAR(1000), "
                + "create_time TIMESTAMP, update_time TIMESTAMP, status VARCHAR(32))");
        // 与生产一致的 RESTRICT 外键：先删用户后删反馈的实现会在这里 FK 报错
        jdbc.execute("CREATE TABLE tb_feedback(id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, "
                + "feedback VARCHAR(2000), create_time TIMESTAMP, "
                + "FOREIGN KEY(user_id) REFERENCES tb_user(id) ON DELETE RESTRICT)");
        jdbc.update("INSERT INTO tb_user(id, username) VALUES (1, 'alice')");
        jdbc.update("INSERT INTO tb_feedback(user_id, feedback, create_time) VALUES "
                + "(1, '希望增加夜间模式', CURRENT_TIMESTAMP), (1, '很好用', CURRENT_TIMESTAMP)");
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

    @Test
    @DisplayName("注销：提交过反馈的用户也能成功注销，反馈行、用户行与登录 token 一并清掉")
    void deleteAccountRemovesFeedbackUserAndToken() {
        Result result = userService.deleteAccount("token-abc");

        assertEquals(0, result.getCode(), "有反馈的用户注销应成功而不是 FK 报错");
        assertEquals(0, count("tb_feedback"), "用户提交的反馈行应随注销一起删除");
        assertEquals(0, count("tb_user"), "用户行应被删除");
        verify(redis).delete("token-abc");
    }

    @Test
    @DisplayName("注销：用户不存在时返回失败且不动 token")
    void deleteAccountFailsWhenUserMissing() {
        // mock 是跨用例共享的单例，先清掉上一用例的调用记录再校验
        org.mockito.Mockito.reset(redis);
        jdbc.update("DELETE FROM tb_feedback");
        jdbc.update("DELETE FROM tb_user");

        Result result = userService.deleteAccount("token-abc");

        assertEquals(1, result.getCode());
        verify(redis, org.mockito.Mockito.never()).delete("token-abc");
    }
}
