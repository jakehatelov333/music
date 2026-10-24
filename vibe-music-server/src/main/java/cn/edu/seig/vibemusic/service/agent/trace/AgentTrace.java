package cn.edu.seig.vibemusic.service.agent.trace;

import cn.edu.seig.vibemusic.model.agent.AgentJson;
import cn.edu.seig.vibemusic.model.entity.AiTraceSpan;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次 AI 请求的追踪上下文（一个 Trace 实例）。
 *
 * <p><b>线程模型：</b>模型回调与工具执行可能落在不同线程，因此这里只收集到内存列表，
 * 由 {@link AgentTraceRecorder#flush} 在请求结算时统一落库。收集过程用同步块保护，
 * 保证并发回调下 Span 不会丢。
 *
 * <p><b>父子关系：</b>TOOL Span 的父节点是<b>发起该次工具调用的 MODEL Span</b>。
 * 这个关系来自执行器包装层的线程绑定（见 {@code TracedToolExecutors}），
 * 是真实因果，不是按时间顺序推测。
 */
public final class AgentTrace {

    private final String traceId;
    private final String chatId;
    private final Long userId;
    private final String model;
    private final LocalDateTime startedAt = LocalDateTime.now();
    private final long startedNanos = System.nanoTime();

    private final List<AiTraceSpan> spans = new ArrayList<>();
    private final List<LlmDetail> llmCalls = new ArrayList<>();
    private final List<ToolDetail> toolCalls = new ArrayList<>();
    private final List<PromptUse> promptUses = new ArrayList<>();

    /** 当前正在进行的模型调用（用于给工具找父 Span）；同一时刻只有一个。 */
    private String currentModelSpanId;

    private int llmSequence;
    private int toolSequence;
    private Map<String, Object> attributes = new LinkedHashMap<>();

    AgentTrace(String traceId, String chatId, Long userId, String model) {
        this.traceId = traceId;
        this.chatId = chatId;
        this.userId = userId;
        this.model = model;
    }

    public String traceId() { return traceId; }

    public String chatId() { return chatId; }

    public Long userId() { return userId; }

    public String model() { return model; }

    public LocalDateTime startedAt() { return startedAt; }

    public long elapsedMillis() { return (System.nanoTime() - startedNanos) / 1_000_000L; }

    synchronized List<AiTraceSpan> spans() { return new ArrayList<>(spans); }

    synchronized List<LlmDetail> llmCalls() { return new ArrayList<>(llmCalls); }

    synchronized List<ToolDetail> toolCalls() { return new ArrayList<>(toolCalls); }

    synchronized List<PromptUse> promptUses() { return new ArrayList<>(promptUses); }

    synchronized Map<String, Object> attributes() { return new LinkedHashMap<>(attributes); }

    synchronized void putAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    synchronized String currentModelSpanId() { return currentModelSpanId; }

    synchronized void currentModelSpanId(String value) { currentModelSpanId = value; }

    // ------------------------------------------------------------------ span

    synchronized AiTraceSpan addSpan(String spanId, String parentSpanId, String type, String name) {
        AiTraceSpan span = new AiTraceSpan();
        span.setTraceId(traceId);
        span.setSpanId(spanId);
        span.setParentSpanId(parentSpanId);
        span.setChatId(chatId);
        span.setUserId(userId);
        span.setSpanType(type);
        span.setName(name);
        span.setStatus(AiTraceSpan.STATUS_RUNNING);
        span.setStartedAt(LocalDateTime.now());
        span.setCreatedAt(LocalDateTime.now());
        spans.add(span);
        return span;
    }

    synchronized AiTraceSpan findSpan(String spanId) {
        if (spanId == null) return null;
        for (AiTraceSpan span : spans) {
            if (spanId.equals(span.getSpanId())) return span;
        }
        return null;
    }

    synchronized int nextLlmIndex() { return ++llmSequence; }

    synchronized int nextToolIndex() { return ++toolSequence; }

    synchronized void addLlmDetail(LlmDetail detail) { llmCalls.add(detail); }

    synchronized void addToolDetail(ToolDetail detail) { toolCalls.add(detail); }

    /** 执行器实测耗时覆盖工具明细里的耗时（明细可能先于覆盖被创建）。 */
    synchronized void overrideToolDuration(String spanId, long durationMs) {
        for (int i = 0; i < toolCalls.size(); i++) {
            ToolDetail detail = toolCalls.get(i);
            if (spanId.equals(detail.spanId())) {
                toolCalls.set(i, new ToolDetail(detail.spanId(), detail.toolName(), detail.modelToolCallId(),
                        detail.serverToolCallId(), detail.snapshot(), detail.status(), detail.errorCategory(),
                        durationMs));
                return;
            }
        }
    }

    synchronized void addPromptUse(PromptUse use) { promptUses.add(use); }

    /** 结束 Span 并写入耗时；{@code endedAt} 为空时用当前时间。 */
    static void finish(AiTraceSpan span, String status, String errorCategory, String errorMessage) {
        span.setStatus(status);
        span.setEndedAt(LocalDateTime.now());
        span.setDurationMs(Math.max(0, java.time.Duration.between(span.getStartedAt(), span.getEndedAt()).toMillis()));
        span.setErrorCategory(errorCategory);
        span.setErrorMessage(truncate(errorMessage, 500));
    }

    static String attributesJson(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) return null;
        return AgentJson.write(attributes);
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 一次模型调用的明细（落 tb_ai_trace_llm_call）。 */
    record LlmDetail(String spanId, int callIndex, TraceSnapshotBuilder.LlmSnapshot snapshot, String finishReason) {
    }

    /** 一次工具调用的明细（落 tb_ai_trace_tool_call）。 */
    record ToolDetail(String spanId, String toolName, String modelToolCallId, String serverToolCallId,
                      TraceSnapshotBuilder.ToolSnapshot snapshot, String status, String errorCategory,
                      Long durationMs) {
    }

    /** 某次模型调用实际生效的基础提示词版本。 */
    record PromptUse(String promptHash, String promptVersion, String basePrompt) {
    }
}
