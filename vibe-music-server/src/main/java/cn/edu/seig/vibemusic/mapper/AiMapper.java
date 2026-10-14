package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.entity.ChatMessage;
import cn.edu.seig.vibemusic.model.vo.ConversationVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AiMapper extends BaseMapper<ChatMessage> {

    /**
     * 确保会话行存在，并顺带补上归属。
     *
     * <p><b>为什么 {@code user_id} 用 COALESCE 而不是直接覆盖：</b>归属一旦确定就不该被改写。
     * 这个方法在每次运行开始时都会走到（{@code acquireConversation}），那里拿不到 userId
     * （传 null）。如果直接覆盖，已经在 {@code resolve} 阶段落好的归属会被抹掉，
     * 会话就会从列表里消失。COALESCE 保证「只在空的时候写入」，同时让
     * 早期版本留下的 user_id 为 NULL 的旧行能在下一次使用时自动补齐。
     *
     * <p>{@code created_at} / {@code last_active_at} 用 ON DUPLICATE 分支不更新：
     * 时间由 {@link #touchConversation} 在真正写入消息时推进，避免"打开面板"这类空操作
     * 把会话顶到列表最前面。
     */
    @Insert("""
            INSERT INTO ai_chat_state(chat_id, user_id, created_at, last_active_at)
            VALUES(#{chatId}, #{userId}, #{now}, #{now})
            ON DUPLICATE KEY UPDATE user_id = COALESCE(user_id, VALUES(user_id))
            """)
    void ensureConversation(@Param("chatId") String chatId, @Param("userId") Long userId,
                            @Param("now") LocalDateTime now);

    /**
     * 会话归属用户；游客会话或行不存在时返回 {@code null}。
     *
     * <p><b>这是越权判定的唯一依据。</b>刻意不做「按 chat_id 前缀匹配」——
     * {@code LIKE 'user_chat_id_12%'} 会命中 {@code user_chat_id_123}，
     * 等于把别人的会话列表交出去。归属必须按列等值比较。
     */
    @Select("SELECT user_id FROM ai_chat_state WHERE chat_id = #{chatId}")
    Long findConversationOwner(@Param("chatId") String chatId);

    /** 推进最近活跃时间。只在真正产生消息时调用，因此不会出现"空会话置顶"。 */
    @Update("UPDATE ai_chat_state SET last_active_at = #{now} WHERE chat_id = #{chatId}")
    int touchConversation(@Param("chatId") String chatId, @Param("now") LocalDateTime now);

    /** 只在标题为空时写入：标题取自首条用户消息，后续消息不得覆盖。 */
    @Update("""
            UPDATE ai_chat_state SET title = #{title}
            WHERE chat_id = #{chatId} AND (title IS NULL OR title = '')
            """)
    int updateTitleIfEmpty(@Param("chatId") String chatId, @Param("title") String title);

    /**
     * 当前用户的最近会话列表。
     *
     * <p><b>{@code EXISTS} 子查询不是可选项：</b>会话行在运行开始时就可能被创建，
     * 如果那一轮失败（模型报错、用户中途断开），就会留下一行没有消息的空会话。
     * 用「至少有一条消息」过滤，才能保证列表里不会出现点进去什么都没有的条目。
     * 该子查询走 {@code idx_chat_messages_chat_id_id} 索引，代价可以忽略。
     *
     * <p>{@code lastMessage} 只取最近一条用户/助手消息：工具调用请求与结果都是 JSON 噪音，
     * 展示给用户没有意义。
     */
    @Select("""
            SELECT s.chat_id, s.title, s.summary_text, s.created_at, s.last_active_at,
                   (SELECT LEFT(m.content, 80) FROM chat_messages m
                     WHERE m.chat_id = s.chat_id AND m.message_type IN ('USER','ASSISTANT')
                     ORDER BY m.id DESC LIMIT 1) AS last_message
            FROM ai_chat_state s
            WHERE s.user_id = #{userId}
              AND EXISTS (SELECT 1 FROM chat_messages m WHERE m.chat_id = s.chat_id)
            ORDER BY s.last_active_at DESC, s.created_at DESC
            LIMIT #{limit}
            """)
    List<ConversationVO> listConversations(@Param("userId") Long userId, @Param("limit") int limit);

    /**
     * 删除会话行。{@code user_id} 是 WHERE 的一部分而不是先查后删：
     * 归属校验必须和删除是同一条语句，否则两者之间的窗口就是一次越权机会。
     *
     * @return 1 表示确实删掉了一行属于该用户的会话
     */
    @Delete("DELETE FROM ai_chat_state WHERE chat_id = #{chatId} AND user_id = #{userId}")
    int deleteConversationRow(@Param("chatId") String chatId, @Param("userId") Long userId);

    @Update("""
            UPDATE ai_chat_state
            SET active_run_token = #{runToken},
                lease_expires_at = TIMESTAMPADD(SECOND, #{leaseSeconds}, CURRENT_TIMESTAMP(6))
            WHERE chat_id = #{chatId}
              AND (active_run_token IS NULL OR lease_expires_at <= CURRENT_TIMESTAMP(6))
            """)
    int acquireConversation(@Param("chatId") String chatId, @Param("runToken") String runToken,
                            @Param("leaseSeconds") long leaseSeconds);

    @Update("""
            UPDATE ai_chat_state SET active_run_token = NULL, lease_expires_at = NULL
            WHERE chat_id = #{chatId} AND active_run_token = #{runToken}
            """)
    int releaseConversation(@Param("chatId") String chatId, @Param("runToken") String runToken);

    @Select("SELECT context_json FROM ai_chat_state WHERE chat_id = #{chatId}")
    String getModelContext(@Param("chatId") String chatId);

    @Update("""
            UPDATE ai_chat_state
            SET context_json = #{contextJson}, context_version = context_version + 1
            WHERE chat_id = #{chatId} AND active_run_token = #{runToken}
              AND lease_expires_at > CURRENT_TIMESTAMP(6)
            """)
    int updateModelContext(@Param("chatId") String chatId, @Param("runToken") String runToken,
                           @Param("contextJson") String contextJson);

    // ------------------------------------------------------------------ 滚动摘要记忆

    @Select("SELECT summary_text FROM ai_chat_state WHERE chat_id = #{chatId}")
    String getSummaryText(@Param("chatId") String chatId);

    @Select("SELECT summary_upto_id FROM ai_chat_state WHERE chat_id = #{chatId}")
    Long getSummaryUptoId(@Param("chatId") String chatId);

    /** 取摘要尚未覆盖的最早一段消息（只取有文本价值的用户/助手消息）。 */
    @Select("""
            SELECT * FROM chat_messages
            WHERE chat_id = #{chatId} AND id > #{afterId} AND message_type IN ('USER','ASSISTANT')
            ORDER BY id ASC LIMIT #{limit}
            """)
    List<ChatMessage> messagesAfter(@Param("chatId") String chatId, @Param("afterId") long afterId,
                                    @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM chat_messages WHERE chat_id = #{chatId} AND id > #{afterId}")
    long countAfter(@Param("chatId") String chatId, @Param("afterId") long afterId);

    /**
     * CAS 写入摘要。返回 0 表示另一个并发任务已经推进过 summary_upto_id，
     * 本次模型结果必须丢弃（重试没有意义，下一轮会自然补齐）。
     */
    @Update("""
            UPDATE ai_chat_state
            SET summary_text = #{summary}, summary_upto_id = #{newUptoId}, summary_updated_at = #{now}
            WHERE chat_id = #{chatId} AND summary_upto_id = #{expectedUptoId}
            """)
    int casSummary(@Param("chatId") String chatId, @Param("expectedUptoId") long expectedUptoId,
                   @Param("newUptoId") long newUptoId, @Param("summary") String summary,
                   @Param("now") LocalDateTime now);

    /**
     * 清空会话时连带清掉摘要。摘要是对话内容的压缩产物，
     * 用户删除聊天记录后它必须一起消失 —— 这是隐私问题，不是优化项。
     */
    @Update("""
            UPDATE ai_chat_state
            SET summary_text = NULL, summary_upto_id = 0, summary_updated_at = NULL
            WHERE chat_id = #{chatId}
            """)
    int clearSummary(@Param("chatId") String chatId);
}
