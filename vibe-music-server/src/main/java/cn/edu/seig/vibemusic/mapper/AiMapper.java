package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.entity.ChatMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
}
