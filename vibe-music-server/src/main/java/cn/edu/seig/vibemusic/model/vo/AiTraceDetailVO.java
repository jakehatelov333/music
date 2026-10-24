package cn.edu.seig.vibemusic.model.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Trace 详情：汇总信息 + 执行树 + 每步明细。
 *
 * <p><b>汇总口径：</b>Token / 耗时 / 次数优先取自逐次模型调用明细（MODEL Span），
 * 与 {@code tb_ai_task_metric} 的汇总做一致性对照，不一致时在 {@code consistency}
 * 里明确标出 —— 不掩盖差异，也不让两个口径互相覆盖。
 */
@Data
public class AiTraceDetailVO {

    private String traceId;
    private String chatId;
    private Long userId;
    private String username;
    private String status;
    private String failureCategory;
    private String failureMessage;
    private String model;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Long latencyMs;

    private Long inputTokens;
    private Long outputTokens;
    private Long totalTokens;
    private String tokenSource;

    private Integer modelCalls;
    private Integer toolCalls;
    private Integer toolFailures;
    private Boolean outputGuarded;

    /** 请求摘要（脱敏短文本）。 */
    private String requestSummary;
    /** 提示词版本与动态块状态（来自 CONTEXT Span）。 */
    private Object context;
    /** 估算费用；未配置价格时为 null，界面显示"未配置价格"。 */
    private AiCostVO cost;

    private List<AiSpanVO> spans;
    private List<AiLlmCallVO> llmCalls;
    private List<AiToolCallVO> toolCallsDetail;

    /** 追踪数据完整性说明：没有 Span 时说明原因（采集关闭 / 历史数据 / 落库失败）。 */
    private String traceNote;
    /** 汇总与明细的一致性核对结果。 */
    private Object consistency;
}
