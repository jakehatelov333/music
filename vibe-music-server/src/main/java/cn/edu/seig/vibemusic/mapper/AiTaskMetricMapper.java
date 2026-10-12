package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.entity.AiTaskMetric;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AiTaskMetricMapper {

    @Insert("""
            INSERT INTO tb_ai_task_metric
                (task_id, user_id, chat_id, model, model_calls, tool_calls, tool_failures,
                 input_tokens, output_tokens, token_source, result_count, success,
                 failure_category, failure_message, output_guarded, latency_ms, playlist_id, created_at)
            VALUES (#{taskId}, #{userId}, #{chatId}, #{model}, #{modelCalls}, #{toolCalls}, #{toolFailures},
                    #{inputTokens}, #{outputTokens}, #{tokenSource}, #{resultCount}, #{success},
                    #{failureCategory}, #{failureMessage}, #{outputGuarded}, #{latencyMs}, #{playlistId},
                    #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AiTaskMetric metric);

    @Select("SELECT * FROM tb_ai_task_metric WHERE task_id = #{taskId} ORDER BY id DESC LIMIT 1")
    AiTaskMetric findByTaskId(String taskId);

    /** 只返回该用户自己的任务指标，避免通过接口看到他人的会话与用量。 */
    @Select("""
            SELECT * FROM tb_ai_task_metric
            WHERE user_id = #{userId}
            ORDER BY id DESC LIMIT #{limit}
            """)
    List<AiTaskMetric> findRecentByUser(@Param("userId") Long userId, @Param("limit") int limit);
}
