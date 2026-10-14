package cn.edu.seig.vibemusic.agent;

import cn.edu.seig.vibemusic.mapper.AiMapper;
import cn.edu.seig.vibemusic.model.entity.ChatMessage;
import cn.edu.seig.vibemusic.model.vo.ClearConversationsResultVO;
import cn.edu.seig.vibemusic.model.vo.ConversationVO;
import cn.edu.seig.vibemusic.service.agent.ConversationService;
import cn.edu.seig.vibemusic.service.impl.AiServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 批量清空全部会话的验收：租约冲突、空会话、列表上限之外的会话、越权隔离、待确认任务、
 * 幂等、以及失败不谎报成功。
 *
 * <p>沿用 {@link AbstractAgentTest} 基座（真实 MySQL 兼容 H2 + 真实 mapper SQL + 真实业务服务），
 * 因为这里要验证的正是<b>数据库层面</b>的事实：消息/摘要/会话行/待确认任务是否真的被清掉、
 * 别的用户的数据是否真的毫发无损。只看返回的计数是验证不了这些的。
 */
class ClearAllConversationsTest extends AbstractAgentTest {

    private ConversationService conversations;

    @BeforeEach
    void wireConversationService() {
        conversations = new ConversationService(aiMapper, actionMapper, aiService());
        ReflectionTestUtils.setField(conversations, "listLimit", 20);
    }

    // ------------------------------------------------------------------ 并发冲突

    @Test
    @DisplayName("存在正在生成的会话时返回 409，且消息、摘要、待确认任务、会话行全部原样不动")
    void activeRunCauses409AndChangesNothing() {
        login(1L);
        String chat = conversations.resolve(1L, null, true);
        append(chat, "正在生成中的对话");
        jdbc.update("UPDATE ai_chat_state SET summary_text = '旧摘要' WHERE chat_id = ?", chat);
        insertPending(1L, chat, "PENDING", "task-busy");

        // 模拟一次仍在进行中的生成：抢下 60 秒租约且不释放。
        // 令牌列是 VARCHAR(36)，正好是 UUID 的长度 —— 不要加任何前缀。
        String busyToken = UUID.randomUUID().toString();
        assertEquals(1, aiMapper.acquireConversation(chat, busyToken, 60));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> conversations.clearAll(1L));
        assertEquals(409, ex.getStatusCode().value());

        // 关键：一个字节都没被删
        assertEquals(1, messageCount(chat), "冲突时不得删除任何消息");
        assertEquals(1L, aiMapper.findConversationOwner(chat), "冲突时不得删除会话行");
        assertEquals("旧摘要", aiMapper.getSummaryText(chat), "冲突时不得清空摘要");
        assertEquals("PENDING", pendingStatus("task-busy"), "冲突时不得取消待确认任务");
        assertEquals(busyToken, jdbc.queryForObject(
                "SELECT active_run_token FROM ai_chat_state WHERE chat_id = ?", String.class, chat),
                "别人正在持有的租约不能被本次尝试覆盖或清掉");
    }

    // ------------------------------------------------------------------ 覆盖范围

    @Test
    @DisplayName("没有任何消息的空会话也会被清除（列表看不见它，但库里必须清掉）")
    void emptyConversationIsAlsoCleared() {
        login(1L);
        String empty = conversations.resolve(1L, null, true); // 建了行，但一句话没说
        String withMessage = conversations.resolve(1L, null, true);
        append(withMessage, "有消息的会话");
        // 列表确实看不到空会话 —— 这正是「不能按列表来删」的原因
        assertEquals(List.of(withMessage),
                conversations.list(1L, 20).stream().map(ConversationVO::getChatId).toList());

        ClearConversationsResultVO result = conversations.clearAll(1L);

        assertEquals(2, result.getConversationCount(), "空会话也计入删除的会话数");
        assertNull(aiMapper.findConversationOwner(empty), "空会话行必须被清除");
        assertNull(aiMapper.findConversationOwner(withMessage));
        assertEquals(0, count("ai_chat_state"));
    }

    @Test
    @DisplayName("超过列表上限的会话（造 25 个）全部被清除，不受 list-limit=20 影响")
    void conversationsBeyondListLimitAreAllCleared() {
        login(1L);
        for (int i = 0; i < 25; i++) {
            String chat = conversations.resolve(1L, null, true);
            append(chat, "会话 " + i);
        }
        assertEquals(20, conversations.list(1L, 20).size(), "列表接口本身受 20 条上限约束");
        assertEquals(25, count("ai_chat_state"));

        ClearConversationsResultVO result = conversations.clearAll(1L);

        assertEquals(25, result.getConversationCount(), "必须删掉全部 25 个，而不是列表里的 20 个");
        assertEquals(0, count("ai_chat_state"));
        assertEquals(0, count("chat_messages"));
    }

    @Test
    @DisplayName("默认会话 user_chat_id_<userId> 被清除，之后能重新建出干净的默认会话")
    void defaultConversationIsClearedAndRecreated() {
        login(1L);
        String def = conversations.resolve(1L, null, false);
        assertEquals("user_chat_id_1", def);
        append(def, "默认会话里的历史");

        ClearConversationsResultVO result = conversations.clearAll(1L);

        assertEquals(1, result.getConversationCount());
        assertNull(aiMapper.findConversationOwner("user_chat_id_1"), "默认会话行应被删除");

        // 清除之后必须还能正常继续用：重建默认会话，归属正确、没有脏租约
        String recreated = conversations.resolve(1L, null, false);
        assertEquals("user_chat_id_1", recreated);
        assertEquals(1L, aiMapper.findConversationOwner(recreated));
        assertNull(jdbc.queryForObject(
                "SELECT active_run_token FROM ai_chat_state WHERE chat_id = ?", String.class, recreated));
    }

    // ------------------------------------------------------------------ 越权隔离

    @Test
    @DisplayName("只清除当前用户：另一个用户的会话行与消息完好无损")
    void onlyCurrentUsersConversationsAreCleared() {
        String aliceChat = conversations.resolve(1L, null, true);
        String bobChat = conversations.resolve(2L, null, true);
        append(aliceChat, "Alice 的对话");
        append(bobChat, "Bob 的对话");
        insertPending(2L, bobChat, "PENDING", "task-bob");

        ClearConversationsResultVO result = conversations.clearAll(1L);

        assertEquals(1, result.getConversationCount());
        assertNull(aiMapper.findConversationOwner(aliceChat));
        assertEquals(0, messageCount(aliceChat));
        // Bob 的一动不动
        assertEquals(2L, aiMapper.findConversationOwner(bobChat));
        assertEquals(1, messageCount(bobChat));
        assertEquals("PENDING", pendingStatus("task-bob"), "别的用户的待确认任务不得被取消");
    }

    // ------------------------------------------------------------------ 待确认任务

    @Test
    @DisplayName("取消该用户全部 PENDING 任务（含孤儿会话的任务），但不动 SUCCEEDED 与别的用户")
    void cancelsOnlyCurrentUsersPendingTasks() {
        login(1L);
        String chat = conversations.resolve(1L, null, true);
        append(chat, "帮我建歌单");

        insertPending(1L, chat, "PENDING", "task-pending-in-chat");
        // 会话行已不存在、但仍能被确认的孤儿任务 —— 正是「用户看不到却能点确认」的那一类
        insertPending(1L, "ghost-chat", "PENDING", "task-orphan");
        insertPending(1L, chat, "SUCCEEDED", "task-done");
        insertPending(2L, chat, "PENDING", "task-other-user");

        ClearConversationsResultVO result = conversations.clearAll(1L);

        assertEquals(2, result.getCancelledTaskCount(), "两个 PENDING 都应被取消");
        assertEquals("CANCELLED", pendingStatus("task-pending-in-chat"));
        assertEquals("CANCELLED", pendingStatus("task-orphan"), "孤儿任务也必须被取消");
        assertEquals("SUCCEEDED", pendingStatus("task-done"), "已成功的任务不得被改写");
        assertEquals("PENDING", pendingStatus("task-other-user"), "别的用户的任务不得被改写");
    }

    // ------------------------------------------------------------------ 幂等

    @Test
    @DisplayName("重复调用幂等：第二次调用返回全 0，不再产生任何副作用")
    void repeatedCallsAreIdempotent() {
        login(1L);
        String chat = conversations.resolve(1L, null, true);
        append(chat, "一段对话");

        ClearConversationsResultVO first = conversations.clearAll(1L);
        assertEquals(1, first.getConversationCount());
        assertEquals(1, first.getMessageCount());

        ClearConversationsResultVO second = conversations.clearAll(1L);
        assertEquals(0, second.getConversationCount());
        assertEquals(0, second.getMessageCount());
        assertEquals(0, second.getSummaryCount());
        assertEquals(0, second.getCancelledTaskCount());
    }

    // ------------------------------------------------------------------ 失败不谎报成功

    /**
     * 「部分删除失败」不能变成「部分成功被报成全部成功」。
     *
     * <p>这里用一个最小可控替身：{@link AdditionalAnswers#delegatesTo} 让 mock 把所有调用原样
     * 转发给真实 mapper，只把 {@code deleteMessagesByUser} 改成抛异常 —— 失败点精确、可复现，
     * 而且没有被 mock 掉任何数据库事实（其他语句仍是真的在执行）。
     *
     * <p>注：{@code ConversationService} 是 {@code @Transactional} 的，生产环境（Spring 代理生效）
     * 会整体回滚；测试里是手工 new 出来的对象、没有代理，所以这里验证的是「异常必须抛出、
     * 绝不返回成功计数」以及「租约必须被释放」，而不是数据库层面的回滚。
     */
    @Test
    @DisplayName("清理中途失败时抛出异常而不是返回成功计数，并释放租约")
    void partialFailureThrowsInsteadOfReportingSuccess() {
        login(1L);
        String chat = conversations.resolve(1L, null, true);
        append(chat, "一段对话");

        AiMapper failing = Mockito.mock(AiMapper.class, AdditionalAnswers.delegatesTo(aiMapper));
        Mockito.doThrow(new IllegalStateException("模拟删除消息失败"))
                .when(failing).deleteMessagesByUser(Mockito.anyLong());
        ConversationService broken = new ConversationService(failing, actionMapper, aiService());
        ReflectionTestUtils.setField(broken, "listLimit", 20);

        assertThrows(IllegalStateException.class, () -> broken.clearAll(1L),
                "失败必须向上抛出，绝不能返回一个声称成功的计数对象");

        // 失败后不能留下脏租约，否则用户在这个会话里会一直「正在生成」而无法说话
        assertNull(jdbc.queryForObject(
                "SELECT active_run_token FROM ai_chat_state WHERE chat_id = ?", String.class, chat),
                "失败路径必须释放本次抢占的租约");
    }

    @Test
    @DisplayName("清除后能创建并使用全新会话")
    void newConversationWorksAfterClear() {
        login(1L);
        String old = conversations.resolve(1L, null, true);
        append(old, "旧对话");
        conversations.clearAll(1L);

        String fresh = conversations.resolve(1L, null, true);
        append(fresh, "新对话");

        assertEquals(1L, aiMapper.findConversationOwner(fresh));
        assertEquals(1, messageCount(fresh));
        assertEquals(List.of(fresh),
                conversations.list(1L, 20).stream().map(ConversationVO::getChatId).toList());
    }

    // ------------------------------------------------------------------ helpers

    /** 通过真实服务追加一条用户消息；租约用完即释放，避免影响后续清空。 */
    private void append(String chatId, String text) {
        AiServiceImpl ai = aiService();
        String token = UUID.randomUUID().toString();
        ai.acquireConversation(chatId, token, 30);
        ai.appendConversation(chatId, token, "[\"ctx\"]",
                List.of(ChatMessage.builder().messageType(ChatMessage.MessageType.USER).content(text).build()));
        ai.releaseConversation(chatId, token);
    }

    private AiServiceImpl aiService() {
        AiServiceImpl ai = new AiServiceImpl();
        ReflectionTestUtils.setField(ai, "baseMapper", aiMapper);
        return ai;
    }

    private void insertPending(Long userId, String chatId, String status, String taskId) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO tb_agent_pending_action"
                        + "(task_id,user_id,chat_id,action_type,draft_json,preview_json,version,status,"
                        + "created_at,updated_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                taskId, userId, chatId, "CREATE_PLAYLIST", "{}", "{}", 1, status,
                now, now, now.plusHours(1));
    }

    private String pendingStatus(String taskId) {
        return jdbc.queryForObject("SELECT status FROM tb_agent_pending_action WHERE task_id = ?",
                String.class, taskId);
    }

    private int messageCount(String chatId) {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_messages WHERE chat_id = ?", Integer.class, chatId);
        return value == null ? 0 : value;
    }
}
