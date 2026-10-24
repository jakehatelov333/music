package cn.edu.seig.vibemusic.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Trace 列表行。
 *
 * <p>{@code requestSummary} 只保存脱敏后的短文本（见 {@code TraceSnapshotBuilder#requestSummary}），
 * 列表页永远不返回完整对话。
 */
@Data
public class AiTraceListItemVO {

    private String traceId;
    private String chatId;
    private Long userId;
    private String username;
    /** SUCCESS / FAILED / TIMEOUT / INTERRUPTED / ... */
    private String status;
    private String failureCategory;
    private String failureMessage;
    private String model;
    private Integer modelCalls;
    private Integer toolCalls;
    private Integer toolFailures;
    private Long inputTokens;
    private Long outputTokens;
    private Long totalTokens;
    private String tokenSource;
    private Long latencyMs;
    private Boolean outputGuarded;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    /** 脱敏后的请求摘要；采集关闭或无法提取时为 null。 */
    private String requestSummary;
    /** 是否已经有 Trace 明细（Span）；没有时详情页会说明原因，而不是显示空白执行树。 */
    private Boolean traced;
}
