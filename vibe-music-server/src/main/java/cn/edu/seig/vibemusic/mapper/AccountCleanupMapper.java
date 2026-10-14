package cn.edu.seig.vibemusic.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 账号注销时对「没有外键、不会随 tb_user 级联」的表做显式清理。
 *
 * <p>这些表没有指向 {@code tb_user} 的外键（或外键刻意未建立），因此删除用户行不会自动清理，
 * 必须在注销事务里显式删除：
 * <ul>
 *     <li>{@code ai_chat_state}：会话运行态/归属，按 {@code user_id}；</li>
 *     <li>{@code chat_messages}：聊天消息，按会话 {@code chat_id}（先查出该用户的 chat_id）；</li>
 *     <li>{@code tb_ai_task_metric}：Agent 任务指标，按 {@code user_id}。</li>
 * </ul>
 */
@Mapper
public interface AccountCleanupMapper {

    /** 该用户拥有的全部会话 id；聊天消息按 chat_id 删除。 */
    @Select("SELECT chat_id FROM ai_chat_state WHERE user_id = #{userId}")
    List<String> selectChatIdsByUserId(@Param("userId") Long userId);

    /**
     * 按会话 id 批量删除聊天消息。
     * 调用方需保证 {@code chatIds} 非空（空集合会生成非法的 {@code IN ()}）。
     */
    @Delete("""
            <script>
            DELETE FROM chat_messages WHERE chat_id IN
            <foreach collection="chatIds" item="chatId" open="(" separator="," close=")">#{chatId}</foreach>
            </script>
            """)
    int deleteChatMessagesByChatIds(@Param("chatIds") List<String> chatIds);

    @Delete("DELETE FROM ai_chat_state WHERE user_id = #{userId}")
    int deleteChatStateByUserId(@Param("userId") Long userId);

    @Delete("DELETE FROM tb_ai_task_metric WHERE user_id = #{userId}")
    int deleteTaskMetricsByUserId(@Param("userId") Long userId);
}
