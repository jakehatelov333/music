package cn.edu.seig.vibemusic.service.agent;

import cn.edu.seig.vibemusic.mapper.AgentPendingActionMapper;
import cn.edu.seig.vibemusic.mapper.AiMapper;
import cn.edu.seig.vibemusic.model.vo.ConversationVO;
import cn.edu.seig.vibemusic.service.IAiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 会话的生命周期与归属。
 *
 * <p><b>这个类存在的理由：</b>改造之前 {@code chatId} 是身份的派生值 —— 登录用户恒为
 * {@code user_chat_id_{userId}}，服务端自己算出来，客户端无从干预，因此"能不能读这个会话"
 * 是个不需要回答的问题。多会话之后 {@code chatId} 变成客户端可以指定、用户拥有多个的资源，
 * 归属校验就从一个隐含事实变成了必须显式执行的一步。把这一步收在一个类里，
 * 是为了让所有入口都只能走同一条判定路径，而不是每个 Controller 各写一遍。
 *
 * <p><b>三条硬规则：</b>
 * <ol>
 *     <li><b>归属按列等值比较</b>，绝不用 {@code chat_id} 前缀匹配 ——
 *         {@code LIKE 'user_chat_id_12%'} 会命中 {@code user_chat_id_123}；</li>
 *     <li><b>客户端传入的 chatId 必须已经存在且属于调用者</b>，不存在就 404，
 *         绝不"顺手创建"——否则任何人可以抢注任意会话 ID；</li>
 *     <li><b>删除会话时连带清理关联状态</b>（待确认任务、摘要、消息），
 *         删了对话却留下可执行的待确认动作是隐私与一致性双重问题。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    /** 默认会话的命名前缀。与 phase7 之前完全一致，因此老用户的历史对话仍然可见、可续。 */
    public static final String USER_PREFIX = "user_chat_id_";

    private final AiMapper aiMapper;
    private final AgentPendingActionMapper actions;
    private final IAiService aiService;

    @Value("${ai.conversation.list-limit:20}")
    private int listLimit;

    /** 默认会话 ID：一个用户升级前就有的那一段对话。 */
    public static String defaultChatId(Long userId) {
        return USER_PREFIX + userId;
    }

    /**
     * 解析本次请求要用的会话，并确保归属正确。
     *
     * @param userId         服务端认证得到的用户 ID，非 null（调用方已保证是登录用户）
     * @param chatId         客户端指定的会话；可为空表示"沿用默认会话"
     * @param newConversation true 表示开一段全新对话，此时 {@code chatId} 被忽略
     * @return 实际使用的 chatId
     */
    public String resolve(Long userId, String chatId, boolean newConversation) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录后再使用会话");
        }
        LocalDateTime now = LocalDateTime.now();
        if (newConversation) {
            // 会话行在这里就落库，而不是等消息写进来才建：
            // 列表查询用「至少有一条消息」过滤空会话，因此提前建行不会产生空条目。
            String created = USER_PREFIX + userId + "_" + UUID.randomUUID();
            aiMapper.ensureConversation(created, userId, now);
            return created;
        }
        if (chatId == null || chatId.isBlank()) {
            String fallback = defaultChatId(userId);
            // 默认会话在首次使用时才建行，归属一并落好。
            aiMapper.ensureConversation(fallback, userId, now);
            return fallback;
        }
        requireOwned(chatId, userId);
        // 走到这里说明归属已经确认，这里的 ensureConversation 只是兜底补行，
        // 不会改写既有归属（SQL 用 COALESCE）。
        aiMapper.ensureConversation(chatId, userId, now);
        return chatId;
    }

    /**
     * 归属校验。不通过一律 404，不区分"不存在"与"不属于你" ——
     * 返回 403 等于确认了这个会话存在，本身就是一条信息泄漏。
     */
    public void requireOwned(String chatId, Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录后再使用会话");
        }
        if (chatId == null || chatId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少会话标识");
        }
        // 默认会话按定义就属于当前用户，且可能在首次使用前还没有数据库行。
        if (defaultChatId(userId).equals(chatId)) return;
        Long owner = aiMapper.findConversationOwner(chatId);
        if (owner == null || !owner.equals(userId)) {
            log.info("会话归属校验未通过 userId={} chatId={}", userId, chatId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在或无权访问");
        }
    }

    /** 当前用户的最近会话，按最近活跃时间倒序。空会话不会出现（见 AiMapper#listConversations）。 */
    public List<ConversationVO> list(Long userId, Integer limit) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录后再查看历史对话");
        }
        int size = limit == null ? listLimit : Math.min(Math.max(limit, 1), listLimit);
        return aiMapper.listConversations(userId, size);
    }

    /**
     * 清空一段对话的内容，但保留这段对话本身（用户还能在里面继续说）。
     *
     * <p><b>为什么连带取消待确认任务：</b>确认卡片是对话内容的一部分。清空了聊天记录却把
     * 一张「点击即可创建歌单」的卡片留在服务端，等于删了记录却留着动作 —— 用户看不到它，
     * 但它仍然可被调用。这是隐私与一致性双重问题。
     */
    public void clear(Long userId, String chatId) {
        requireOwned(chatId, userId);
        int cancelled = actions.cancelPendingByChat(userId, chatId, LocalDateTime.now());
        aiService.deleteMessagesByChatId(chatId);
        log.info("清空会话 chatId={} 取消待确认任务={}", chatId, cancelled);
    }

    /**
     * 彻底删除一段对话：清空内容之后，再删掉会话行，让它从「最近对话」里消失。
     *
     * <p><b>顺序有讲究：</b>先走 {@link #clear}，其中的
     * {@link IAiService#deleteMessagesByChatId} 会先抢会话租约 ——
     * 若此刻正在生成回复会返回 409，这正是我们想要的：删到一半的对话比不删更糟。
     * 租约释放之后再删会话行，顺序反过来会留下一个没有租约保护的空壳。
     */
    public void delete(Long userId, String chatId) {
        clear(userId, chatId);
        int removed = aiMapper.deleteConversationRow(chatId, userId);
        log.info("删除会话 chatId={} 删除会话行={}", chatId, removed);
    }
}
