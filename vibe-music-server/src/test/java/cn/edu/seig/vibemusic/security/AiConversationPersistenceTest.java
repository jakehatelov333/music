package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.mapper.AiMapper;
import cn.edu.seig.vibemusic.model.entity.ChatMessage;
import cn.edu.seig.vibemusic.service.IAiService;
import cn.edu.seig.vibemusic.service.impl.AiServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 MyBatis + Spring 事务，使用独立 H2/MySQL 模式数据库验证，不连接开发数据库。 */
@SpringJUnitConfig(AiConversationPersistenceTest.Config.class)
class AiConversationPersistenceTest {
    @TestConfiguration
    @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:chat_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            return source;
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.addMapper(AiMapper.class);
            MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
            bean.setDataSource(dataSource);
            bean.setConfiguration(configuration);
            return bean.getObject();
        }
        @Bean AiMapper aiMapper(SqlSessionFactory factory) { return new SqlSessionTemplate(factory).getMapper(AiMapper.class); }
        @Bean IAiService aiService(AiMapper mapper) {
            AiServiceImpl service = new AiServiceImpl();
            ReflectionTestUtils.setField(service, "baseMapper", mapper);
            return service;
        }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
    }

    @Autowired IAiService service;
    @Autowired DataSource source;
    private JdbcTemplate jdbc;
    private String chat;

    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ai_chat_state(
                  chat_id VARCHAR(255) PRIMARY KEY, context_json LONGTEXT NULL,
                  context_version BIGINT NOT NULL DEFAULT 0,
                  active_run_token VARCHAR(36) NULL, lease_expires_at TIMESTAMP(6) NULL,
                  summary_text LONGTEXT NULL, summary_upto_id BIGINT NOT NULL DEFAULT 0,
                  summary_updated_at TIMESTAMP(6) NULL)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS chat_messages(
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, chat_id VARCHAR(255) NOT NULL,
                  message_type VARCHAR(32) NOT NULL, content LONGTEXT NOT NULL,
                  created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL)
                """);
        chat = "test_" + UUID.randomUUID();
    }

    private ChatMessage message(String text) {
        return ChatMessage.builder().messageType(ChatMessage.MessageType.USER).content(text).build();
    }

    @Test void activeRunBlocksSameConversationAndClearButNotAnotherConversation() {
        service.acquireConversation(chat, "first", 60);
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.acquireConversation(chat, "second", 60)).getStatusCode().value());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.deleteMessagesByChatId(chat)).getStatusCode().value());
        service.acquireConversation(chat + "_other", "second", 60);
        service.appendConversation(chat, "first", "[\"context\"]", List.of(message("保留消息")));
        ChatMessage saved = service.getMessagesByChatId(chat).get(0);
        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
        service.releaseConversation(chat, "first");
        // 先置一份摘要，验证清空会话时摘要被连带清除（隐私：删了记录不能留着内容）
        jdbc.update("UPDATE ai_chat_state SET summary_text = '旧对话的摘要' WHERE chat_id = ?", chat);
        service.deleteMessagesByChatId(chat);
        assertEquals(0, service.countMessagesByChatId(chat));
        assertEquals("[]", service.getModelContext(chat));
        assertNull(jdbc.queryForObject("SELECT summary_text FROM ai_chat_state WHERE chat_id = ?", String.class, chat));
        assertNull(jdbc.queryForObject("SELECT active_run_token FROM ai_chat_state WHERE chat_id = ?", String.class, chat));
    }

    @Test void expiredRunCannotWriteOrReleaseTheSuccessorLease() {
        service.acquireConversation(chat, "old", 60);
        jdbc.update("UPDATE ai_chat_state SET lease_expires_at = TIMESTAMP '2000-01-01 00:00:00' WHERE chat_id = ?", chat);
        service.acquireConversation(chat, "new", 60);
        assertThrows(ResponseStatusException.class,
                () -> service.appendConversation(chat, "old", "[\"stale\"]", List.of(message("迟到消息"))));
        service.releaseConversation(chat, "old");
        assertEquals("new", jdbc.queryForObject("SELECT active_run_token FROM ai_chat_state WHERE chat_id = ?", String.class, chat));
        service.appendConversation(chat, "new", "[]", List.of(message("新消息")));
        assertEquals(List.of("新消息"), service.getMessagesByChatId(chat).stream().map(ChatMessage::getContent).toList());
    }

    @Test void failedAppendRollsBackBothTheWindowAndEarlierRowsInTheSameBatch() {
        service.acquireConversation(chat, "run", 60);
        service.appendConversation(chat, "run", "[]", List.of(message("原始消息")));
        ChatMessage original = service.getMessagesByChatId(chat).get(0);
        assertThrows(RuntimeException.class, () -> service.appendConversation(chat, "run", "[\"changed\"]",
                List.of(message("本事务第一条"), message(null))));
        assertEquals("[]", service.getModelContext(chat));
        assertEquals(1, service.countMessagesByChatId(chat));
        assertEquals(original, service.getMessagesByChatId(chat).get(0));
    }

    @Test void concurrentAcquisitionHasExactlyOneWinner() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> acquire = () -> {
            start.await();
            try { service.acquireConversation(chat, UUID.randomUUID().toString(), 60); return true; }
            catch (ResponseStatusException busy) { assertEquals(409, busy.getStatusCode().value()); return false; }
        };
        try {
            Future<Boolean> first = pool.submit(acquire), second = pool.submit(acquire);
            start.countDown();
            assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
}
