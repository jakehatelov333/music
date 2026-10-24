package cn.edu.seig.vibemusic.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI Trace 执行树中的一个步骤（Span）。
 *
 * <p><b>层级约定：</b>
 * <ul>
 *     <li>{@code REQUEST} 是根，{@code parentSpanId = null}；</li>
 *     <li>{@code CONTEXT} / {@code MODEL} / {@code GUARD} / {@code FINAL} 直接挂在 REQUEST 下；</li>
 *     <li>{@code TOOL} 挂在<b>发起该次工具调用的 MODEL Span</b> 下 —— 这是真实因果关系，
 *         不是按时间顺序猜的（见 {@code AgentTraceRecorder} 的线程绑定）。</li>
 * </ul>
 *
 * <p>{@code spanId} 对 TOOL 类型与 SSE {@code toolCallId} 完全一致，因此前端事件流与
 * 后端执行树可以直接对照。
 */
@Data
public class AiTraceSpan {

    public static final String TYPE_REQUEST = "REQUEST";
    public static final String TYPE_CONTEXT = "CONTEXT";
    public static final String TYPE_MODEL = "MODEL";
    public static final String TYPE_TOOL = "TOOL";
    public static final String TYPE_GUARD = "GUARD";
    public static final String TYPE_FINAL = "FINAL";

    public static final String STATUS_OK = "OK";
    public static final String STATUS_ERROR = "ERROR";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_EMPTY = "EMPTY";
    public static final String STATUS_RUNNING = "RUNNING";

    private Long id;
    private String traceId;
    private String spanId;
    private String parentSpanId;
    private String chatId;
    private Long userId;
    private String spanType;
    private String name;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Long durationMs;
    private String model;
    private Long inputTokens;
    private Long outputTokens;
    private String tokenSource;
    private String errorCategory;
    private String errorMessage;
    /** 仅根 Span 使用：脱敏后的请求摘要，供列表页展示（不返回完整对话）。 */
    private String requestSummary;
    /** 脱敏后的结构化属性 JSON（提示词版本、注入块状态、护栏结论等）。 */
    private String attributesJson;
    private LocalDateTime createdAt;
}
