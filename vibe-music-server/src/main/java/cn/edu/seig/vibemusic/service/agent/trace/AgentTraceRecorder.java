package cn.edu.seig.vibemusic.service.agent.trace;

import cn.edu.seig.vibemusic.mapper.AiTraceMapper;
import cn.edu.seig.vibemusic.model.agent.AgentJson;
import cn.edu.seig.vibemusic.model.agent.ToolResult;
import cn.edu.seig.vibemusic.model.entity.AiTraceLlmCall;
import cn.edu.seig.vibemusic.model.entity.AiTracePrompt;
import cn.edu.seig.vibemusic.model.entity.AiTraceSpan;
import cn.edu.seig.vibemusic.model.entity.AiTraceToolCall;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Trace 采集入口。业务代码只调用这里的方法，不直接碰表结构。
 *
 * <p><b>采集绝不能影响用户回复：</b>每个采集方法内部吞掉所有异常并记 warn，
 * 同时累加 {@code failures} 计数，由 {@code GET /admin/ai/overview} 暴露出来 ——
 * 出问题时是"看得见的降级"，而不是悄悄丢数据。
 *
 * <p><b>为什么用内存缓冲而不是边执行边写库：</b>一次请求可能产生十几个 Span，
 * 逐条写库会把模型调用之外的时间浪费在数据库往返上；而且中途失败时半张执行树更难解释。
 * 请求结算时一次性写入，写入失败就整条 Trace 不落库并计数（指标表仍然独立落库）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTraceRecorder {

    private final AiTraceMapper mapper;
    private final TraceSnapshotBuilder snapshots;
    private final AgentTraceSettings settings;

    /** 采集失败计数（进程级），供总览页显示"追踪系统自身的健康度"。 */
    private final AtomicLong failures = new AtomicLong();

    public long failures() { return failures.get(); }

    // ------------------------------------------------------------------ 生命周期

    /**
     * 开始一次请求的追踪。
     *
     * @return 追踪上下文；采集关闭或初始化失败时返回 {@code null}，调用方据此跳过后续采集
     */
    public AgentTrace begin(String traceId, String chatId, Long userId, String model) {
        if (!settings.enabled() || traceId == null) return null;
        try {
            AgentTrace trace = new AgentTrace(traceId, chatId, userId, model);
            AiTraceSpan root = trace.addSpan(traceId, null, AiTraceSpan.TYPE_REQUEST, "请求初始化");
            root.setModel(model);
            return trace;
        } catch (RuntimeException e) {
            recordFailure("begin", e);
            return null;
        }
    }

    /**
     * 记录上下文 / 系统提示词组装结果。
     *
     * <p><b>这里记录的是"实际拼出来的东西"：</b>{@code prompt} 就是
     * {@code AgentSystemPrompt#assemble} 的返回值，会立刻交给模型；
     * 动态块的注入状态来自组装者的实测结果，而不是"我们以为会注入什么"。
     */
    public void contextBuilt(AgentTrace trace, String prompt, String basePrompt, String promptVersion,
                             String promptHash, Map<String, Object> blockStates) {
        if (trace == null) return;
        try {
            AiTraceSpan span = trace.addSpan(trace.traceId() + "-context", trace.traceId(),
                    AiTraceSpan.TYPE_CONTEXT, "上下文与提示词构建");
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("basePromptVersion", promptVersion);
            attributes.put("basePromptHash", promptHash);
            attributes.put("effectivePromptChars", prompt == null ? 0 : prompt.length());
            attributes.put("basePromptChars", basePrompt == null ? 0 : basePrompt.length());
            attributes.put("blocks", blockStates == null ? Map.of() : blockStates);
            // 完整提示词快照：受 capture-content 控制，关闭时只留长度与哈希。
            if (settings.captureContent() && prompt != null) {
                attributes.put("effectivePrompt", snapshots.redact(prompt));
            } else if (!settings.captureContent()) {
                attributes.put("note", "内容快照已关闭，仅保留长度与哈希");
            }
            span.setAttributesJson(AgentTrace.attributesJson(attributes));
            AgentTrace.finish(span, AiTraceSpan.STATUS_OK, null, null);

            if (promptHash != null && basePrompt != null) {
                trace.addPromptUse(new AgentTrace.PromptUse(promptHash, promptVersion, basePrompt));
            }
            trace.putAttribute("promptVersion", promptVersion);
            trace.putAttribute("promptHash", promptHash);
            trace.putAttribute("blocks", blockStates);
        } catch (RuntimeException e) {
            recordFailure("context", e);
        }
    }

    /** 记录一次输入摘要（只存脱敏后的短文本，不存整段对话）。 */
    public void requestSummary(AgentTrace trace, String summary) {
        if (trace == null || summary == null) return;
        try {
            // 根 Span 上存一份短字段，列表页据此展示；完整摘要仍进属性（详情页用）。
            AiTraceSpan root = trace.findSpan(trace.traceId());
            if (root != null) root.setRequestSummary(summary.length() <= 300 ? summary : summary.substring(0, 300));
            trace.putAttribute("requestSummary", summary);
        } catch (RuntimeException e) {
            recordFailure("summary", e);
        }
    }

    /**
     * 记录用户本轮问题（脱敏、截断后的短文本），用于列表页摘要。
     *
     * <p>不存完整对话：列表页只需要"这条 Trace 在问什么"，完整输入在模型调用明细里，
     * 受同一套权限与保留策略约束。
     */
    public void userQuery(AgentTrace trace, String query) {
        if (trace == null || query == null) return;
        try {
            String summary = snapshots.requestSummary(List.of(
                    dev.langchain4j.data.message.UserMessage.from(query)), 200);
            if (summary != null) requestSummary(trace, summary);
        } catch (RuntimeException e) {
            recordFailure("userQuery", e);
        }
    }

    // ------------------------------------------------------------------ 模型调用

    /**
     * 登记一次模型调用并返回其 span ID。
     *
     * <p>在调用<b>之前</b>调用：模型抛错时这条记录仍然存在（状态 ERROR），
     * 执行树里能看到"第几次调用失败了"。
     */
    public String beginModelCall(AgentTrace trace, List<ChatMessage> messages, List<ToolSpecification> tools) {
        if (trace == null) return null;
        try {
            int index = trace.nextLlmIndex();
            String spanId = trace.traceId() + "-llm-" + index;
            AiTraceSpan span = trace.addSpan(spanId, trace.traceId(), AiTraceSpan.TYPE_MODEL, "模型调用 #" + index);
            span.setModel(traceModel(trace));
            // 后续该模型轮次里发生的工具调用，父节点就是它。
            trace.currentModelSpanId(spanId);
            return spanId;
        } catch (RuntimeException e) {
            recordFailure("beginModel", e);
            return null;
        }
    }

    /** 结束一次模型调用，写入 Token、输出与明细快照。 */
    public void endModelCall(AgentTrace trace, String spanId, List<ChatMessage> messages,
                             List<ToolSpecification> tools, Response<AiMessage> response, Throwable error) {
        if (trace == null || spanId == null) return;
        try {
            AiTraceSpan span = trace.findSpan(spanId);
            if (span == null) return;
            if (error != null) {
                AgentTrace.finish(span, AiTraceSpan.STATUS_ERROR, error.getClass().getSimpleName(),
                        error.getMessage());
                trace.currentModelSpanId(null);
                // 失败的调用同样留下输入快照：否则无法判断"是不是输入本身有问题"。
                trace.addLlmDetail(new AgentTrace.LlmDetail(spanId, llmIndex(spanId),
                        snapshots.llmCall(messages, tools, null, null), null));
                return;
            }
            TokenUsage usage = response == null ? null : response.tokenUsage();
            if (usage != null) {
                span.setInputTokens(toLong(usage.inputTokenCount()));
                span.setOutputTokens(toLong(usage.outputTokenCount()));
                span.setTokenSource("PROVIDER");
            } else {
                span.setTokenSource("UNAVAILABLE");
            }
            AiMessage content = response == null ? null : response.content();
            String finishReason = response == null || response.finishReason() == null ? null
                    : response.finishReason().name();
            AgentTrace.finish(span, AiTraceSpan.STATUS_OK, null, null);
            trace.addLlmDetail(new AgentTrace.LlmDetail(spanId, llmIndex(spanId),
                    snapshots.llmCall(messages, tools, content, finishReason), finishReason));
        } catch (RuntimeException e) {
            recordFailure("endModel", e);
        }
    }

    // ------------------------------------------------------------------ 工具调用

    /**
     * 登记一次工具执行，返回工具 Span 的 ID（同时会作为 SSE toolCallId）。
     *
     * @param modelRequest 模型侧的原始请求；为空表示这次执行不是模型请求触发的
     */
    public String beginToolCall(AgentTrace trace, ToolExecutionRequest modelRequest, String toolName) {
        if (trace == null) return null;
        try {
            int index = trace.nextToolIndex();
            String spanId = trace.traceId() + "-tool-" + index;
            String parent = trace.currentModelSpanId();
            AiTraceSpan span = trace.addSpan(spanId, parent == null ? trace.traceId() : parent,
                    AiTraceSpan.TYPE_TOOL, "工具执行 " + toolName);
            span.setModel(null);
            return spanId;
        } catch (RuntimeException e) {
            recordFailure("beginTool", e);
            return null;
        }
    }

    /**
     * 结束一次工具执行。
     *
     * @param modelRequest 模型原始请求（可为 null）
     * @param result       工具返回的业务结果；为空表示执行器抛出了异常
     * @param rawResult    工具返回给模型的原始字符串（已序列化）
     */
    public void endToolCall(AgentTrace trace, String spanId, ToolExecutionRequest modelRequest,
                            String toolName, ToolResult<?> result, String rawResult, Throwable error) {
        if (trace == null || spanId == null) return;
        try {
            AiTraceSpan span = trace.findSpan(spanId);
            if (span == null) return;
            String status = statusOf(result, error);
            String errorCategory = result != null && !result.ok() ? result.code()
                    : error != null ? error.getClass().getSimpleName() : null;
            AgentTrace.finish(span, status, errorCategory,
                    result != null ? result.message() : error == null ? null : error.getMessage());

            String parsedArguments = parseArguments(modelRequest);
            TraceSnapshotBuilder.ToolSnapshot snapshot = snapshots.toolCall(
                    modelRequest == null ? null : modelRequest.arguments(),
                    parsedArguments,
                    rawResult,
                    result == null ? null : result.message(),
                    result == null ? 0 : result.total(),
                    result == null ? null : result.code(),
                    result == null || result.ok(),
                    errorCategory);
            trace.addToolDetail(new AgentTrace.ToolDetail(spanId, toolName,
                    modelRequest == null ? null : modelRequest.id(), spanId, snapshot, status, errorCategory,
                    span.getDurationMs()));
        } catch (RuntimeException e) {
            recordFailure("endTool", e);
        }
    }

    /**
     * 参数解析的实测记录：把模型给的原始 JSON 解析成 Map 再序列化。
     *
     * <p>刻意不"修正"参数 —— 这里只记录服务端实际看到的内容。若模型给了非法 JSON，
     * 解析失败本身就说明了问题，写入 {@code null} 并在返回码里体现。
     */
    public String parseArguments(ToolExecutionRequest request) {
        if (request == null || request.arguments() == null) return null;
        try {
            Object parsed = AgentJson.MAPPER.readValue(request.arguments(), Map.class);
            return AgentJson.write(parsed);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 用执行器的实测耗时覆盖工具 Span 的耗时。
     *
     * <p>工具内部的计时不含参数类型转换与异常包装；执行器看到的才是"这次调用真正花了多久"。
     * 两者差异通常很小，但排障时以执行器为准更接近用户感知。
     */
    public void overrideToolDuration(AgentTrace trace, String spanId, long durationMs) {
        if (trace == null || spanId == null) return;
        try {
            AiTraceSpan span = trace.findSpan(spanId);
            if (span != null) span.setDurationMs(durationMs);
            trace.overrideToolDuration(spanId, durationMs);
        } catch (RuntimeException e) {
            recordFailure("toolDuration", e);
        }
    }

    // ------------------------------------------------------------------ 结束

    /**
     * 记录输出护栏的结论，并暂存最终输出。
     *
     * <p>护栏在<b>结算之前</b>执行（见 {@code AiController.StreamSession#settle} 的钩子），
     * 因此这里能拿到"护栏替换后"的文本；最终输出节点在 {@link #finish} 里补写，
     * 保证执行树顺序是：模型调用 → 护栏 → 最终输出。
     */
    public void guardApplied(AgentTrace trace, boolean guarded, String finalText, String reason) {
        if (trace == null) return;
        try {
            AiTraceSpan span = trace.addSpan(trace.traceId() + "-guard", trace.traceId(),
                    AiTraceSpan.TYPE_GUARD, guarded ? "输出护栏命中" : "输出护栏检查");
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("guarded", guarded);
            if (reason != null) attributes.put("reason", reason);
            span.setAttributesJson(AgentTrace.attributesJson(attributes));
            AgentTrace.finish(span, guarded ? AiTraceSpan.STATUS_REJECTED : AiTraceSpan.STATUS_OK, null, null);
            trace.putAttribute("outputGuarded", guarded);
            if (reason != null) trace.putAttribute("guardReason", reason);
            if (finalText != null) trace.putAttribute("finalText", snapshots.redact(finalText));
        } catch (RuntimeException e) {
            recordFailure("guard", e);
        }
    }

    /** 结束根 Span，写入最终状态。 */
    public void finish(AgentTrace trace, boolean success, String failureCategory, String failureMessage,
                       long latencyMillis) {
        if (trace == null) return;
        try {
            AiTraceSpan root = trace.findSpan(trace.traceId());
            if (root != null) {
                if (!success) {
                    AgentTrace.finish(root, AiTraceSpan.STATUS_ERROR, failureCategory, failureMessage);
                } else {
                    AgentTrace.finish(root, AiTraceSpan.STATUS_OK, null, null);
                }
                root.setDurationMs(latencyMillis);
                // 汇总 Token 写在根 Span 上，便于列表页一次查询取到；明细仍以 MODEL Span 为准。
                long input = 0, output = 0;
                boolean any = false;
                for (AiTraceSpan span : trace.spans()) {
                    if (AiTraceSpan.TYPE_MODEL.equals(span.getSpanType())) {
                        if (span.getInputTokens() != null) { input += span.getInputTokens(); any = true; }
                        if (span.getOutputTokens() != null) { output += span.getOutputTokens(); any = true; }
                    }
                }
                if (any) {
                    root.setInputTokens(input);
                    root.setOutputTokens(output);
                    root.setTokenSource("PROVIDER");
                } else {
                    root.setTokenSource("UNAVAILABLE");
                }
                root.setAttributesJson(AgentTrace.attributesJson(trace.attributes()));
            }
            // 最终输出节点：回答正文本身就是最有价值的排查材料，但必须脱敏后保存。
            finishFinalSpan(trace, success, failureCategory);
        } catch (RuntimeException e) {
            recordFailure("finish", e);
        }
    }

    /**
     * 记录"最终输出"节点。
     *
     * <p>与护栏节点分开：护栏节点记录<b>是否被替换</b>，这里记录<b>用户最终看到的内容</b>。
     * 两者合在一起会让"被护栏改写前的原文"无处可查。
     */
    private void finishFinalSpan(AgentTrace trace, boolean success, String failureCategory) {
        Object finalText = trace.attributes().get("finalText");
        if (finalText == null) return;
        AiTraceSpan span = trace.addSpan(trace.traceId() + "-final", trace.traceId(),
                AiTraceSpan.TYPE_FINAL, "最终输出");
        java.util.Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("chars", finalText.toString().length());
        if (settings.captureContent()) attributes.put("text", finalText.toString());
        span.setAttributesJson(AgentTrace.attributesJson(attributes));
        AgentTrace.finish(span, success ? AiTraceSpan.STATUS_OK : AiTraceSpan.STATUS_ERROR,
                success ? null : failureCategory, null);
    }

    /**
     * 把内存中的 Trace 一次性落库。
     *
     * <p>顺序：Span → LLM 明细 → 工具明细 → 提示词版本。Span 先写，明细的外键语义
     * （逻辑关联，非物理外键）才有对象可指。任何一步失败都只记日志与计数，
     * <b>绝不抛给调用方</b> —— 用户已经拿到回复了。
     */
    public void flush(AgentTrace trace) {
        if (trace == null || !settings.enabled()) return;
        try {
            List<AiTraceSpan> spans = trace.spans();
            for (AiTraceSpan span : spans) {
                // 兜底：没结束的 Span 也要有结束时间，否则界面上永远是"进行中"。
                if (span.getEndedAt() == null) {
                    AgentTrace.finish(span, AiTraceSpan.STATUS_ERROR, "INCOMPLETE", "追踪未正常结束");
                }
                mapper.insertSpan(span);
            }
            for (AgentTrace.LlmDetail detail : trace.llmCalls()) {
                AiTraceLlmCall row = new AiTraceLlmCall();
                row.setSpanId(detail.spanId());
                row.setTraceId(trace.traceId());
                row.setCallIndex(detail.callIndex());
                row.setMessagesJson(detail.snapshot().messagesJson());
                row.setSystemMessage(detail.snapshot().systemMessage());
                row.setPromptHash(detail.snapshot().promptHash());
                row.setToolsJson(detail.snapshot().toolsJson());
                row.setToolsHash(detail.snapshot().toolsHash());
                row.setOutputText(detail.snapshot().outputText());
                row.setToolRequestsJson(detail.snapshot().toolRequestsJson());
                row.setFinishReason(detail.finishReason());
                row.setSnapshotState(detail.snapshot().state());
                row.setSnapshotNote(detail.snapshot().note());
                row.setCreatedAt(LocalDateTime.now());
                mapper.insertLlmCall(row);
            }
            for (AgentTrace.ToolDetail detail : trace.toolCalls()) {
                AiTraceToolCall row = new AiTraceToolCall();
                row.setSpanId(detail.spanId());
                row.setTraceId(trace.traceId());
                row.setToolName(detail.toolName());
                row.setModelToolCallId(detail.modelToolCallId());
                row.setServerToolCallId(detail.serverToolCallId());
                row.setRawArguments(detail.snapshot().rawArguments());
                row.setParsedArguments(detail.snapshot().parsedArguments());
                row.setStatus(detail.status());
                row.setResultCode(detail.snapshot().resultCode());
                row.setResultTotal(detail.snapshot().resultTotal());
                row.setResultSummary(detail.snapshot().resultSummary());
                row.setResultPayload(detail.snapshot().resultPayload());
                row.setErrorCategory(detail.errorCategory());
                row.setDurationMs(detail.durationMs() != null ? detail.durationMs()
                        : durationOf(spans, detail.spanId()));
                row.setSnapshotState(detail.snapshot().state());
                row.setSnapshotNote(detail.snapshot().note());
                row.setCreatedAt(LocalDateTime.now());
                mapper.insertToolCall(row);
            }
            for (AgentTrace.PromptUse use : trace.promptUses()) {
                if (use.promptHash() == null) continue;
                mapper.upsertPrompt(use.promptHash(), use.promptVersion(), use.basePrompt(), LocalDateTime.now());
            }
        } catch (RuntimeException e) {
            recordFailure("flush", e);
        }
    }

    // ------------------------------------------------------------------ 内部

    private void recordFailure(String stage, RuntimeException error) {
        failures.incrementAndGet();
        log.warn("AI Trace 采集失败 stage={} type={}", stage, error.getClass().getSimpleName());
    }

    private static String traceModel(AgentTrace trace) {
        return trace.model();
    }

    private static int llmIndex(String spanId) {
        int index = spanId.lastIndexOf('-');
        if (index < 0) return 0;
        try {
            return Integer.parseInt(spanId.substring(index + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Long durationOf(List<AiTraceSpan> spans, String spanId) {
        for (AiTraceSpan span : spans) {
            if (spanId.equals(span.getSpanId())) return span.getDurationMs();
        }
        return null;
    }

    private static String statusOf(ToolResult<?> result, Throwable error) {
        if (error != null) return AiTraceSpan.STATUS_ERROR;
        if (result == null) return AiTraceSpan.STATUS_ERROR;
        if (result.ok()) {
            return "EMPTY".equals(result.code()) ? AiTraceSpan.STATUS_EMPTY : AiTraceSpan.STATUS_OK;
        }
        return "REJECTED".equals(result.code()) || "INVALID_ARGUMENT".equals(result.code())
                || "UNAUTHORIZED".equals(result.code()) || "NOT_FOUND".equals(result.code())
                ? AiTraceSpan.STATUS_REJECTED
                : AiTraceSpan.STATUS_ERROR;
    }

    private static Long toLong(Integer value) {
        return value == null ? null : value.longValue();
    }

    /** 供测试与诊断：当前是否有采集能力。 */
    public boolean enabled() {
        return settings.enabled();
    }
}
