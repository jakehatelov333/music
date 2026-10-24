package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.entity.AiTraceLlmCall;
import cn.edu.seig.vibemusic.model.entity.AiTraceSpan;
import cn.edu.seig.vibemusic.model.entity.AiTraceToolCall;
import cn.edu.seig.vibemusic.model.vo.AiOverviewVO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Trace 写入与聚合查询。
 *
 * <p><b>为什么聚合放在 SQL 而不是 Java：</b>Trace 表会随时间增长，把全部记录读进内存
 * 再统计会让总览页随数据量线性变慢，还会把数据库压力转成应用内存压力。
 * 这里所有统计都是服务端聚合，返回的是已经算好的行。
 *
 * <p><b>为什么不用 {@code DATE_FORMAT}：</b>H2（测试与离线回归）不支持 MySQL 的
 * {@code DATE_FORMAT}。改用标准 SQL 的 {@code EXTRACT} + {@code CAST(... AS DATE)}，
 * 两边都能跑，避免"测试用一套 SQL、生产用另一套"。
 *
 * <p><b>SQL 全部参数绑定</b>，不拼接任何前端输入；排序字段在 Service 层经白名单映射后再传入。
 */
@Mapper
public interface AiTraceMapper {

    // ------------------------------------------------------------------ 写入

    @Insert("""
            INSERT INTO tb_ai_trace_span
                (trace_id, span_id, parent_span_id, chat_id, user_id, span_type, name, status,
                 started_at, ended_at, duration_ms, model, input_tokens, output_tokens, token_source,
                 error_category, error_message, request_summary, attributes_json, created_at)
            VALUES (#{traceId}, #{spanId}, #{parentSpanId}, #{chatId}, #{userId}, #{spanType}, #{name}, #{status},
                    #{startedAt}, #{endedAt}, #{durationMs}, #{model}, #{inputTokens}, #{outputTokens}, #{tokenSource},
                    #{errorCategory}, #{errorMessage}, #{requestSummary}, #{attributesJson}, #{createdAt})
            """)
    int insertSpan(AiTraceSpan span);

    @Insert("""
            INSERT INTO tb_ai_trace_llm_call
                (span_id, trace_id, call_index, messages_json, system_message, prompt_hash, tools_json,
                 tools_hash, output_text, tool_requests_json, finish_reason, snapshot_state, snapshot_note, created_at)
            VALUES (#{spanId}, #{traceId}, #{callIndex}, #{messagesJson}, #{systemMessage}, #{promptHash}, #{toolsJson},
                    #{toolsHash}, #{outputText}, #{toolRequestsJson}, #{finishReason}, #{snapshotState},
                    #{snapshotNote}, #{createdAt})
            """)
    int insertLlmCall(AiTraceLlmCall call);

    @Insert("""
            INSERT INTO tb_ai_trace_tool_call
                (span_id, trace_id, tool_name, model_tool_call_id, server_tool_call_id, raw_arguments,
                 parsed_arguments, status, result_code, result_total, result_summary, result_payload,
                 error_category, duration_ms, snapshot_state, snapshot_note, created_at)
            VALUES (#{spanId}, #{traceId}, #{toolName}, #{modelToolCallId}, #{serverToolCallId}, #{rawArguments},
                    #{parsedArguments}, #{status}, #{resultCode}, #{resultTotal}, #{resultSummary}, #{resultPayload},
                    #{errorCategory}, #{durationMs}, #{snapshotState}, #{snapshotNote}, #{createdAt})
            """)
    int insertToolCall(AiTraceToolCall call);

    /**
     * 提示词版本登记（幂等）。
     *
     * <p>MySQL 与 H2 都支持 {@code ON DUPLICATE KEY UPDATE}（H2 的 MySQL 兼容模式）。
     * 只更新"最近出现时间/次数"，<b>不改原文</b> —— 同一哈希对应同一份提示词，
     * 改写原文意味着哈希碰撞，那属于必须人工介入的异常。
     */
    @Insert("""
            INSERT INTO tb_ai_trace_prompt(prompt_hash, prompt_version, base_prompt, first_seen_at, last_seen_at, seen_count)
            VALUES (#{hash}, #{version}, #{basePrompt}, #{now}, #{now}, 1)
            ON DUPLICATE KEY UPDATE last_seen_at = VALUES(last_seen_at), seen_count = seen_count + 1
            """)
    int upsertPrompt(@Param("hash") String hash, @Param("version") String version,
                     @Param("basePrompt") String basePrompt, @Param("now") LocalDateTime now);

    // ------------------------------------------------------------------ 读取

    @Select("SELECT * FROM tb_ai_trace_span WHERE trace_id = #{traceId} ORDER BY started_at, id")
    List<AiTraceSpan> findSpans(@Param("traceId") String traceId);

    @Select("SELECT * FROM tb_ai_trace_llm_call WHERE trace_id = #{traceId} ORDER BY call_index")
    List<AiTraceLlmCall> findLlmCalls(@Param("traceId") String traceId);

    @Select("SELECT * FROM tb_ai_trace_llm_call WHERE span_id = #{spanId} LIMIT 1")
    AiTraceLlmCall findLlmCallBySpan(@Param("spanId") String spanId);

    @Select("SELECT * FROM tb_ai_trace_tool_call WHERE trace_id = #{traceId} ORDER BY id")
    List<AiTraceToolCall> findToolCalls(@Param("traceId") String traceId);

    @Select("SELECT * FROM tb_ai_trace_tool_call WHERE span_id = #{spanId} LIMIT 1")
    AiTraceToolCall findToolCallBySpan(@Param("spanId") String spanId);

    @Select("SELECT base_prompt FROM tb_ai_trace_prompt WHERE prompt_hash = #{hash}")
    String findBasePrompt(@Param("hash") String hash);

    @Select("SELECT prompt_version FROM tb_ai_trace_prompt WHERE prompt_hash = #{hash}")
    String findPromptVersion(@Param("hash") String hash);

    @Select("SELECT COUNT(*) FROM tb_ai_trace_span WHERE trace_id = #{traceId}")
    int countSpans(@Param("traceId") String traceId);

    /**
     * 用过某个工具的 trace_id 集合（用于按工具名过滤）。
     *
     * <p>做成子查询集合而不是 JOIN：一次请求可能调用同一工具多次，JOIN 会让列表出现重复行，
     * 进而让分页总数失真。
     */
    @Select("""
            SELECT DISTINCT trace_id FROM tb_ai_trace_tool_call
            WHERE tool_name = #{toolName} AND created_at >= #{from} AND created_at < #{to}
            LIMIT 5000
            """)
    List<String> traceIdsByToolName(@Param("toolName") String toolName,
                                    @Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to);

    // ------------------------------------------------------------------ 总览聚合

    @Select("""
            SELECT COUNT(*)                                              AS totalRequests,
                   COALESCE(SUM(CASE WHEN success = TRUE THEN 1 ELSE 0 END), 0)      AS successRequests,
                   COALESCE(SUM(CASE WHEN success = FALSE AND failure_category = 'TIMEOUT' THEN 1 ELSE 0 END), 0) AS timeoutRequests,
                   COALESCE(SUM(CASE WHEN success = FALSE AND failure_category = 'INTERRUPTED' THEN 1 ELSE 0 END), 0) AS interruptedRequests,
                   COALESCE(SUM(CASE WHEN token_source = 'UNAVAILABLE' THEN 1 ELSE 0 END), 0) AS tokenUnavailable,
                   COALESCE(SUM(input_tokens), 0)                        AS inputTokens,
                   COALESCE(SUM(output_tokens), 0)                       AS outputTokens,
                   COALESCE(SUM(model_calls), 0)                         AS modelCalls,
                   COALESCE(SUM(tool_calls), 0)                          AS toolCalls,
                   COALESCE(SUM(tool_failures), 0)                       AS toolFailures,
                   COALESCE(AVG(latency_ms), 0)                          AS avgLatencyMs,
                   COALESCE(SUM(CASE WHEN output_guarded = TRUE THEN 1 ELSE 0 END), 0) AS guardedRequests
            FROM tb_ai_task_metric
            WHERE created_at >= #{from} AND created_at < #{to}
            """)
    AiOverviewVO totals(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 失败数（排除成功记录），单独查便于语义清晰。 */
    @Select("""
            SELECT COUNT(*) FROM tb_ai_task_metric
            WHERE created_at >= #{from} AND created_at < #{to} AND success = FALSE
            """)
    long countFailed(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 分位数：用窗口函数。MySQL 8 与 H2 2.x 均支持。
     *
     * <p>取"最接近的第 k 个值"（{@code CEIL} 定位），而不是线性插值 ——
     * 响应耗时是离散的实测值，插值出来的数字没有对应的真实请求。
     */
    @Select("""
            SELECT latency_ms FROM (
                SELECT latency_ms,
                       ROW_NUMBER() OVER (ORDER BY latency_ms) AS rn,
                       COUNT(*) OVER () AS total
                FROM tb_ai_task_metric
                WHERE created_at >= #{from} AND created_at < #{to}
            ) ranked
            WHERE rn = CEIL(total * #{percentile})
            LIMIT 1
            """)
    Long percentile(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                    @Param("percentile") double percentile);

    /** 按小时 / 按天趋势。{@code unit} 只接受 HOUR / DAY（Service 层白名单）。 */
    @Select("""
            <script>
            SELECT CONCAT(EXTRACT(YEAR FROM created_at), '-',
                          LPAD(EXTRACT(MONTH FROM created_at), 2, '0'), '-',
                          LPAD(EXTRACT(DAY FROM created_at), 2, '0')
                          <if test="unit == 'HOUR'">, ' ', LPAD(EXTRACT(HOUR FROM created_at), 2, '0'), ':00'</if>
                         ) AS bucket,
                   COUNT(*) AS requests,
                   COALESCE(SUM(input_tokens), 0) AS inputTokens,
                   COALESCE(SUM(output_tokens), 0) AS outputTokens,
                   COALESCE(SUM(CASE WHEN output_guarded = TRUE THEN 1 ELSE 0 END), 0) AS guarded
            FROM tb_ai_task_metric
            WHERE created_at &gt;= #{from} AND created_at &lt; #{to}
            GROUP BY CONCAT(EXTRACT(YEAR FROM created_at), '-',
                            LPAD(EXTRACT(MONTH FROM created_at), 2, '0'), '-',
                            LPAD(EXTRACT(DAY FROM created_at), 2, '0')
                            <if test="unit == 'HOUR'">, ' ', LPAD(EXTRACT(HOUR FROM created_at), 2, '0'), ':00'</if>
                           )
            ORDER BY bucket
            </script>
            """)
    List<AiOverviewVO.TrendPoint> trend(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                        @Param("unit") String unit);

    @Select("""
            SELECT model,
                   COUNT(*) AS requests,
                   COALESCE(SUM(input_tokens), 0) AS inputTokens,
                   COALESCE(SUM(output_tokens), 0) AS outputTokens,
                   COALESCE(AVG(latency_ms), 0) AS avgLatencyMs,
                   COALESCE(SUM(CASE WHEN success = FALSE THEN 1 ELSE 0 END), 0) AS failureCount
            FROM tb_ai_task_metric
            WHERE created_at >= #{from} AND created_at < #{to}
            GROUP BY model
            ORDER BY requests DESC
            """)
    List<java.util.Map<String, Object>> modelStats(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 按模型 + 命中价格档聚合模型调用用量。
     *
     * <p>供应商按<b>单次请求</b>的输入 Token 选档，所以必须先按每个 MODEL Span
     * 判断档位，再汇总；不能用「总输入 Token / 总调用次数」的平均值选档。
     * 分档规则由服务端配置传入，SQL 只做参数绑定。
     */
    @Select("""
            <script>
            SELECT model,
                   token_source,
                   tier_index,
                   COUNT(*) AS calls,
                   COALESCE(SUM(input_tokens), 0) AS inputTokens,
                   COALESCE(SUM(output_tokens), 0) AS outputTokens
            FROM (
                SELECT model,
                       input_tokens,
                       output_tokens,
                       CASE WHEN token_source = 'PROVIDER' AND input_tokens IS NOT NULL
                            THEN token_source ELSE 'UNAVAILABLE' END AS token_source,
                       <foreach collection="tiers" item="tier" separator=" ">
                           <choose>
                               <when test="tier.tierIndex == 0">
                                   CASE WHEN input_tokens &gt; #{tier.minInputTokens}
                                             AND input_tokens &lt;= #{tier.maxInputTokens}
                                        THEN #{tier.tierIndex}
                               </when>
                               <otherwise>
                                   WHEN input_tokens &gt; #{tier.minInputTokens}
                                        AND input_tokens &lt;= #{tier.maxInputTokens}
                                   THEN #{tier.tierIndex}
                               </otherwise>
                           </choose>
                       </foreach>
                       ELSE -1 END AS tier_index
                FROM tb_ai_trace_span
                WHERE span_type = 'MODEL'
                  AND started_at &gt;= #{from} AND started_at &lt; #{to}
            ) priced
            GROUP BY model, token_source, tier_index
            ORDER BY model, tier_index
            </script>
            """)
    List<java.util.Map<String, Object>> costTierStats(@Param("from") LocalDateTime from,
                                                       @Param("to") LocalDateTime to,
                                                       @Param("tiers") List<cn.edu.seig.vibemusic.model.dto.AiCostTierRule> tiers);

    /** 时间范围内任务级模型调用总数，用于识别没有 Trace 明细、因而无法计价的调用。 */
    @Select("""
            SELECT COALESCE(SUM(model_calls), 0)
            FROM tb_ai_task_metric
            WHERE created_at >= #{from} AND created_at < #{to}
            """)
    long countModelCalls(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("""
            SELECT m.user_id AS userId, u.username AS username,
                   COUNT(*) AS requests,
                   COALESCE(SUM(m.input_tokens), 0) + COALESCE(SUM(m.output_tokens), 0) AS totalTokens
            FROM tb_ai_task_metric m
            LEFT JOIN tb_user u ON u.id = m.user_id
            WHERE m.created_at >= #{from} AND m.created_at < #{to} AND m.user_id IS NOT NULL
            GROUP BY m.user_id, u.username
            ORDER BY totalTokens DESC
            LIMIT #{limit}
            """)
    List<java.util.Map<String, Object>> userStats(@Param("from") LocalDateTime from,
                                                  @Param("to") LocalDateTime to,
                                                  @Param("limit") int limit);

    /**
     * 工具统计。
     *
     * <p>数据来自 Trace 的工具明细（真实执行），不是模型声称的调用。
     * 因此「模型说要调工具但后端没执行」不会被算成一次成功调用。
     */
    @Select("""
            SELECT tool_name AS toolName,
                   COUNT(*) AS calls,
                   COALESCE(SUM(CASE WHEN status IN ('ERROR', 'REJECTED') THEN 1 ELSE 0 END), 0) AS failures,
                   COALESCE(AVG(duration_ms), 0) AS avgDurationMs
            FROM tb_ai_trace_tool_call
            WHERE created_at >= #{from} AND created_at < #{to}
            GROUP BY tool_name
            ORDER BY calls DESC
            """)
    List<java.util.Map<String, Object>> toolStats(@Param("from") LocalDateTime from,
                                                  @Param("to") LocalDateTime to);

    @Select("""
            SELECT failure_category AS category, COUNT(*) AS count
            FROM tb_ai_task_metric
            WHERE created_at >= #{from} AND created_at < #{to}
              AND success = FALSE AND failure_category IS NOT NULL
            GROUP BY failure_category
            ORDER BY count DESC
            """)
    List<AiOverviewVO.FailureStat> failureStats(@Param("from") LocalDateTime from,
                                                @Param("to") LocalDateTime to);

    @Select("""
            SELECT task_id AS traceId, model, latency_ms AS latencyMs,
                   CASE WHEN success = TRUE THEN 'SUCCESS' ELSE COALESCE(failure_category, 'FAILED') END AS status,
                   created_at AS startedAt, user_id AS userId
            FROM tb_ai_task_metric
            WHERE created_at >= #{from} AND created_at < #{to}
            ORDER BY latency_ms DESC
            LIMIT #{limit}
            """)
    List<AiOverviewVO.SlowRequest> slowest(@Param("from") LocalDateTime from,
                                           @Param("to") LocalDateTime to,
                                           @Param("limit") int limit);

    // ------------------------------------------------------------------ 保留期清理

    @Delete("DELETE FROM tb_ai_trace_span WHERE started_at < #{before}")
    int deleteSpansBefore(@Param("before") LocalDateTime before);

    @Delete("DELETE FROM tb_ai_trace_llm_call WHERE created_at < #{before}")
    int deleteLlmCallsBefore(@Param("before") LocalDateTime before);

    @Delete("DELETE FROM tb_ai_trace_tool_call WHERE created_at < #{before}")
    int deleteToolCallsBefore(@Param("before") LocalDateTime before);
}
