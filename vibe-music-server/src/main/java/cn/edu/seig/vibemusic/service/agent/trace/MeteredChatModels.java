package cn.edu.seig.vibemusic.service.agent.trace;

import cn.edu.seig.vibemusic.service.agent.AgentMetrics;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模型装饰器：既记任务级指标，也产出<b>逐次调用</b>的 Trace 明细。
 *
 * <p><b>为什么把两件事合在一个装饰器里：</b>它们需要同一份信息（真实消息列表、真实工具定义、
 * 真实响应）。拆成两层包装会让"到底哪一层拿到了什么"变得难以验证，而且多一层调用栈
 * 就多一个可能漏记的位置。这里一次采集、两处落账：{@link AgentMetrics} 记汇总，
 * {@link AgentTraceRecorder} 记明细。
 *
 * <p><b>明细与汇总不重复累计：</b>{@code AgentMetrics} 的 Token 是各次调用的和，
 * Trace 的每个 MODEL Span 各存自己那一次的用量；查询侧通过 Span 求和得到可核对的总量
 * （见 {@code AiTraceQueryService} 的一致性自检）。
 *
 * <p><b>失败也留痕：</b>模型抛错时同样开一个 MODEL Span（状态 ERROR），
 * 这样"调用了几次、第几次失败"在执行树里可见，而不是只看到一个笼统的失败。
 */
public final class MeteredChatModels {

    private MeteredChatModels() {}

    /**
     * @param trace    追踪上下文；为 null 表示本次运行不采集 Trace
     * @param recorder 采集器；trace 与 recorder 必须同时存在才会记录
     */
    public static ChatLanguageModel blocking(ChatLanguageModel model, AgentMetrics metrics,
                                             AgentTrace trace, AgentTraceRecorder recorder) {
        return new ChatLanguageModel() {
            @Override public Response<AiMessage> generate(List<ChatMessage> messages) {
                return call(metrics, trace, recorder, messages, null, () -> model.generate(messages));
            }

            @Override public Response<AiMessage> generate(List<ChatMessage> messages,
                                                          List<ToolSpecification> tools) {
                return call(metrics, trace, recorder, messages, tools, () -> model.generate(messages, tools));
            }

            @Override public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification tool) {
                List<ToolSpecification> single = tool == null ? null : List.of(tool);
                return call(metrics, trace, recorder, messages, single, () -> model.generate(messages, tool));
            }
        };
    }

    /** 失败的模型调用同样计数（只是拿不到用量），否则失败任务的 modelCalls 会被低估为 0。 */
    private static Response<AiMessage> call(AgentMetrics metrics, AgentTrace trace, AgentTraceRecorder recorder,
                                            List<ChatMessage> messages, List<ToolSpecification> tools,
                                            java.util.function.Supplier<Response<AiMessage>> invocation) {
        boolean traced = trace != null && recorder != null;
        // 开始前就登记：模型抛错时也要有一条"这次调用失败"的记录。
        String spanId = traced ? recorder.beginModelCall(trace, messages, tools) : null;
        try {
            Response<AiMessage> response = invocation.get();
            record(metrics, response);
            if (traced) recorder.endModelCall(trace, spanId, messages, tools, response, null);
            return response;
        } catch (RuntimeException e) {
            metrics.modelCalled(null, null);
            if (traced) recorder.endModelCall(trace, spanId, messages, tools, null, e);
            throw e;
        }
    }

    public static StreamingChatLanguageModel streaming(StreamingChatLanguageModel model, AgentMetrics metrics,
                                                       AgentTrace trace, AgentTraceRecorder recorder) {
        return new StreamingChatLanguageModel() {
            @Override public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
                model.generate(messages, metered(handler, metrics, trace, recorder, messages, null));
            }

            @Override public void generate(List<ChatMessage> messages, List<ToolSpecification> tools,
                                           StreamingResponseHandler<AiMessage> handler) {
                model.generate(messages, tools, metered(handler, metrics, trace, recorder, messages, tools));
            }

            @Override public void generate(List<ChatMessage> messages, ToolSpecification tool,
                                           StreamingResponseHandler<AiMessage> handler) {
                List<ToolSpecification> single = tool == null ? null : List.of(tool);
                model.generate(messages, tool, metered(handler, metrics, trace, recorder, messages, single));
            }
        };
    }

    private static StreamingResponseHandler<AiMessage> metered(StreamingResponseHandler<AiMessage> handler,
                                                                AgentMetrics metrics, AgentTrace trace,
                                                                AgentTraceRecorder recorder,
                                                                List<ChatMessage> messages,
                                                                List<ToolSpecification> tools) {
        return new StreamingResponseHandler<>() {
            private final AtomicBoolean recorded = new AtomicBoolean();
            /** 流式调用在第一次回调时才真正开始计时，避免把排队时间算进模型耗时。 */
            private volatile String spanId;

            private String span() {
                if (spanId == null && trace != null && recorder != null) {
                    spanId = recorder.beginModelCall(trace, messages, tools);
                }
                return spanId;
            }

            @Override public void onNext(String token) {
                span();
                handler.onNext(token);
            }

            @Override public void onComplete(Response<AiMessage> response) {
                if (recorded.compareAndSet(false, true)) {
                    record(metrics, response);
                    if (trace != null && recorder != null) {
                        recorder.endModelCall(trace, span(), messages, tools, response, null);
                    }
                }
                handler.onComplete(response);
            }

            @Override public void onError(Throwable error) {
                if (recorded.compareAndSet(false, true)) {
                    metrics.modelCalled(null, null);
                    if (trace != null && recorder != null) {
                        recorder.endModelCall(trace, span(), messages, tools, null, error);
                    }
                }
                handler.onError(error);
            }
        };
    }

    private static void record(AgentMetrics metrics, Response<AiMessage> response) {
        TokenUsage usage = response == null ? null : response.tokenUsage();
        metrics.modelCalled(usage == null ? null : usage.inputTokenCount(),
                usage == null ? null : usage.outputTokenCount());
    }
}
