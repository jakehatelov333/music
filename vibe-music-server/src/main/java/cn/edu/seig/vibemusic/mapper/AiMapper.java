package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.entity.ChatMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AiMapper extends BaseMapper<ChatMessage> {
    @Insert("INSERT INTO ai_chat_state(chat_id) VALUES(#{chatId}) ON DUPLICATE KEY UPDATE chat_id = VALUES(chat_id)")
    void ensureConversation(@Param("chatId") String chatId);

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
