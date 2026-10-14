package cn.edu.seig.vibemusic.agent;

import cn.edu.seig.vibemusic.model.agent.PlaylistDraft;
import cn.edu.seig.vibemusic.model.entity.ChatMessage;
import cn.edu.seig.vibemusic.model.vo.ConversationVO;
import cn.edu.seig.vibemusic.model.vo.PendingActionVO;
import cn.edu.seig.vibemusic.service.agent.ConversationService;
import cn.edu.seig.vibemusic.service.impl.AiServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多会话的验收：归属校验、列表、隔离、与待确认任务的关系。
 *
 * <p>沿用 {@link AbstractAgentTest} 的基座（真实 MySQL 兼容 H2 + 真实 mapper SQL + 真实业务服务），
 * 因为这里要验证的正是<b>数据库层面</b>的事实：越权是否真的读不到、
 * 消息与摘要是否真的分表隔离、删会话是否真的把待确认任务收拾干净。
 * 只看返回值是验证不了这些的。
 */
class ConversationServiceTest extends AbstractAgentTest {

    private ConversationService conversations;

    @BeforeEach
    void wireConversationService() {
        // 用真实的 AiServiceImpl：标题与最近活跃时间的维护就在它的 appendConversation 里，
        // 换成替身就等于把要验证的行为一起绕过去了。
        AiServiceImpl ai = new AiServiceImpl();
        ReflectionTestUtils.setField(ai, "baseMapper", aiMapper);
        conversations = new ConversationService(aiMapper, actionMapper, ai);
        ReflectionTestUtils.setField(conversations, "listLimit", 20);
    }

    // ------------------------------------------------------------------ 归属

    @Test
    @DisplayName("默认会话沿用升级前的命名，老用户的历史对话仍能被识别为本人所有")
    void defaultConversationKeepsTheLegacyName() {
        assertEquals("user_chat_id_1", ConversationService.defaultChatId(1L));
        assertEquals("user_chat_id_1", conversations.resolve(1L, null, false));
        assertEquals(1L, aiMapper.findConversationOwner("user_chat_id_1"));
    }

    @Test
    @DisplayName("新对话生成互不相同的标识，且都归属当前用户")
    void newConversationsAreDistinctAndOwned() {
        String first = conversations.resolve(1L, null, true);
        String second = conversations.resolve(1L, null, true);

        assertNotEquals(first, second);
        assertTrue(first.startsWith("user_chat_id_1_"));
        assertEquals(1L, aiMapper.findConversationOwner(first));
        assertEquals(1L, aiMapper.findConversationOwner(second));
    }

    @Test
    @DisplayName("传别人的会话一律 404，而且失败的尝试不能把归属改成自己")
    void foreignConversationIsRejectedWithoutStealingOwnership() {
        String bobChat = conversations.resolve(2L, null, true);

        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> conversations.resolve(1L, bobChat, false)).getStatusCode().value());
        // 这条断言才是关键：只返回 404 还不够，如果校验过程顺手把 user_id 改成了 1，
        // Alice 就能靠「碰一下」把 Bob 的会话接管过来。
        assertEquals(2L, aiMapper.findConversationOwner(bobChat));
    }

    /**
     * 前缀碰撞：{@code user_chat_id_1} 是 {@code user_chat_id_12} 的前缀。
     * 用 {@code LIKE 'user_chat_id_1%'} 做归属过滤会把用户 12 的会话算进用户 1 名下 ——
     * 这条用例就是钉住「归属必须按列等值比较」这个决定的。
     */
    @Test
    @DisplayName("前缀相似的会话不会被误判成同一个人")
    void prefixCollisionDoesNotGrantAccess() {
        jdbc.update("INSERT INTO tb_user(id, username) VALUES (12,'carol')");
        conversations.resolve(12L, null, false); // 建出 user_chat_id_12

        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> conversations.resolve(1L, "user_chat_id_12", false)).getStatusCode().value());
        assertEquals(12L, aiMapper.findConversationOwner("user_chat_id_12"));
    }

    @Test
    @DisplayName("不存在的会话不会被顺手创建：不能靠猜 ID 抢注")
    void unknownConversationIsNotCreatedOnDemand() {
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> conversations.resolve(1L, "user_chat_id_1_" + UUID.randomUUID(), false))
                .getStatusCode().value());
        assertEquals(0, count("ai_chat_state"));
    }

    @Test
    @DisplayName("游客既没有会话列表，也不能指定会话")
    void guestsHaveNoConversationList() {
        assertNotNull(assertThrows(ResponseStatusException.class, () -> conversations.list(null, 20)));
        assertNotNull(assertThrows(ResponseStatusException.class,
                () -> conversations.resolve(null, null, true)));
    }

    // ------------------------------------------------------------------ 列表

    @Test
    @DisplayName("会话列表按最近活跃时间倒序，标题取自首条用户消息，且不含空会话")
    void listOrdersByRecentActivityTitlesFromFirstMessageAndSkipsEmpty() {
        String older = conversations.resolve(1L, null, true);
        String newer = conversations.resolve(1L, null, true);
        String empty = conversations.resolve(1L, null, true); // 建了行但一句话没说

        append(older, "适合夜间驾驶的歌单");
        append(newer, "周杰伦冷门歌曲推荐");

        // 时间戳显式写死：列表排序是这里要验证的行为，不该依赖两次调用之间的时钟分辨率。
        jdbc.update("UPDATE ai_chat_state SET last_active_at = ? WHERE chat_id = ?",
                LocalDateTime.now().minusMinutes(10), older);
        jdbc.update("UPDATE ai_chat_state SET last_active_at = ? WHERE chat_id = ?",
                LocalDateTime.now(), newer);

        List<ConversationVO> list = conversations.list(1L, 20);
        assertEquals(List.of(newer, older), list.stream().map(ConversationVO::getChatId).toList());
        assertEquals("周杰伦冷门歌曲推荐", list.get(0).getTitle());
        assertEquals("适合夜间驾驶的歌单", list.get(1).getTitle());
        assertFalse(list.stream().anyMatch(c -> c.getChatId().equals(empty)), "空会话不应出现在列表里");
        assertTrue(list.get(0).getLastMessage() != null, "摘要为空时应能回退到最后一条消息");
    }

    @Test
    @DisplayName("列表只包含自己的会话")
    void listOnlyContainsOwnConversations() {
        String mine = conversations.resolve(1L, null, true);
        String theirs = conversations.resolve(2L, null, true);
        append(mine, "Alice 的对话");
        append(theirs, "Bob 的对话");

        List<ConversationVO> list = conversations.list(1L, 20);
        assertEquals(1, list.size());
        assertEquals(mine, list.get(0).getChatId());
        assertEquals(1L, aiMapper.findConversationOwner(list.get(0).getChatId()));
    }

    // ------------------------------------------------------------------ 隔离

    @Test
    @DisplayName("会话之间的消息与摘要互不污染")
    void conversationsDoNotShareMessagesOrSummaries() {
        String a = conversations.resolve(1L, null, true);
        String b = conversations.resolve(1L, null, true);

        append(a, "会话A的消息");
        append(b, "会话B的消息");
        jdbc.update("UPDATE ai_chat_state SET summary_text = 'A的摘要' WHERE chat_id = ?", a);

        assertEquals(List.of("会话A的消息"), messageContents(a));
        assertEquals(List.of("会话B的消息"), messageContents(b));
        assertEquals("A的摘要", aiMapper.getSummaryText(a));
        assertNull(aiMapper.getSummaryText(b), "会话 B 不该继承会话 A 的摘要");
    }

    // ------------------------------------------------------------------ 与待确认任务的关系

    @Test
    @DisplayName("待确认任务按会话隔离：会话 B 里看不到会话 A 的卡片")
    void pendingActionsAreScopedToTheirConversation() {
        login(1L);
        String a = conversations.resolve(1L, null, true);
        String b = conversations.resolve(1L, null, true);
        pendingActions.prepare(1L, a, new PlaylistDraft("夜间驾驶", "简介", "流行", false, ids(10, 11)));

        assertEquals(1, pendingActions.pending(a).size());
        assertEquals(0, pendingActions.pending(b).size(), "切换会话不得把别的会话的卡片带过来");
    }

    @Test
    @DisplayName("切换会话后回到原会话，待确认任务仍可确认，且重复确认只创建一个歌单")
    void pendingActionSurvivesSwitchAndStaysIdempotent() {
        login(1L);
        String a = conversations.resolve(1L, null, true);
        String b = conversations.resolve(1L, null, true);
        PendingActionVO preview = pendingActions.prepare(1L, a,
                new PlaylistDraft("夜间驾驶", "简介", "流行", false, ids(10, 11)));

        // 切走：B 里看不到 A 的任务
        assertEquals(0, pendingActions.pending(b).size());
        // 切回：任务仍在，状态真实
        List<PendingActionVO> backInA = pendingActions.pending(a);
        assertEquals(1, backInA.size());
        assertEquals(preview.getTaskId(), backInA.get(0).getTaskId());
        assertEquals("PENDING", backInA.get(0).getStatus());

        pendingActions.confirm(preview.getTaskId(), preview.getVersion());
        pendingActions.confirm(preview.getTaskId(), preview.getVersion());
        assertEquals(1, count("tb_playlist"), "重复确认不得重复创建歌单");
        assertEquals(0, pendingActions.pending(a).size(), "已完成的卡片不再以待执行状态出现");
    }

    // ------------------------------------------------------------------ 删除

    @Test
    @DisplayName("清空会话：消息与摘要一起消失，但会话本身保留可继续对话")
    void clearConversationRemovesMessagesAndSummaryButKeepsRow() {
        login(1L);
        String chat = conversations.resolve(1L, null, true);
        append(chat, "帮我建个歌单");
        pendingActions.prepare(1L, chat, new PlaylistDraft("夜间驾驶", "简介", "流行", false, ids(10, 11)));
        jdbc.update("UPDATE ai_chat_state SET summary_text = '摘要' WHERE chat_id = ?", chat);

        conversations.clear(1L, chat);

        assertEquals(0, messageCount(chat));
        assertEquals(1L, aiMapper.findConversationOwner(chat), "清空不是删除：会话行应保留，用户还能继续说");
        assertNull(aiMapper.getSummaryText(chat), "摘要是对话内容的压缩产物，清空消息后必须一起消失");
        assertEquals(0L, jdbc.queryForObject(
                "SELECT summary_upto_id FROM ai_chat_state WHERE chat_id = ?", Long.class, chat),
                "摘要游标必须归零，否则残留的 upto_id 会让下次摘要跳过新消息");
        assertEquals(0, pendingActions.pending(chat).size(), "待确认卡片随清空一起取消");
    }

    @Test
    @DisplayName("删除会话：消息、摘要、待确认任务、会话行一起收拾干净")
    void deleteConversationClearsEverythingItOwns() {
        login(1L);
        String chat = conversations.resolve(1L, null, true);
        append(chat, "帮我建个歌单");
        pendingActions.prepare(1L, chat, new PlaylistDraft("夜间驾驶", "简介", "流行", false, ids(10, 11)));
        jdbc.update("UPDATE ai_chat_state SET summary_text = '摘要' WHERE chat_id = ?", chat);

        conversations.delete(1L, chat);

        assertEquals(0, messageCount(chat));
        assertNull(aiMapper.findConversationOwner(chat), "会话行应被移除");
        assertEquals(0, pendingActions.pending(chat).size());
        // 任务被取消而不是删除：留痕，但不能再被确认执行
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM tb_agent_pending_action WHERE chat_id = ?", String.class, chat));
    }

    @Test
    @DisplayName("删除会话不会抹掉已成功任务的追溯凭据")
    void deleteKeepsSucceededTasksForAudit() {
        login(1L);
        String chat = conversations.resolve(1L, null, true);
        PendingActionVO preview = pendingActions.prepare(1L, chat,
                new PlaylistDraft("夜间驾驶", "简介", "流行", false, ids(10, 11)));
        PendingActionVO done = pendingActions.confirm(preview.getTaskId(), preview.getVersion());

        conversations.delete(1L, chat);

        // 已成功的任务记着「这个歌单由这次对话创建」，删会话不该抹掉它
        assertEquals("SUCCEEDED", jdbc.queryForObject(
                "SELECT status FROM tb_agent_pending_action WHERE task_id = ?", String.class, preview.getTaskId()));
        assertEquals(done.getPlaylistId(), jdbc.queryForObject(
                "SELECT result_playlist_id FROM tb_agent_pending_action WHERE task_id = ?",
                Long.class, preview.getTaskId()));
        assertEquals(1, count("tb_playlist"), "删除会话不应影响已创建的歌单");
    }

    @Test
    @DisplayName("不能删除别人的会话")
    void foreignConversationCannotBeDeleted() {
        String bobChat = conversations.resolve(2L, null, true);
        append(bobChat, "Bob 的对话");

        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> conversations.delete(1L, bobChat)).getStatusCode().value());
        assertEquals(1, messageCount(bobChat), "越权删除必须完全无副作用");
        assertEquals(2L, aiMapper.findConversationOwner(bobChat));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 通过真实服务追加一条用户消息，顺带验证标题与最近活跃时间是被正确维护的。
     *
     * <p>必须显式释放租约：{@code acquireConversation} 是按会话加锁的，
     * 不释放的话后续的删除/清空都会以 409 失败 —— 那验证的就不是本次要测的东西了。
     */
    private void append(String chatId, String text) {
        AiServiceImpl ai = aiService();
        // 租约令牌列是 VARCHAR(36)，正好是 UUID 的长度 —— 不要加前缀。
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

    private List<String> messageContents(String chatId) {
        return jdbc.queryForList("SELECT content FROM chat_messages WHERE chat_id = ? ORDER BY id",
                String.class, chatId);
    }

    private int messageCount(String chatId) {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_messages WHERE chat_id = ?", Integer.class, chatId);
        return value == null ? 0 : value;
    }
}
