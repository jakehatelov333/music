package cn.edu.seig.vibemusic.service.agent;

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
 * 模型装饰器：把每次模型调用的次数、耗时与 Token 用量记进 {@link AgentMetrics}。
 *
 * <p>这是“真实 Token 使用量”的唯一来源。DashScope 的响应里带 usage，
 * 因此 {@code Response#tokenUsage()} 是实测值；若供应商未返回（null），
 * 则不计入样本，最终指标里会体现为 {@code tokenSource=UNAVAILABLE}。
 *
 * <p>流式场景下 AiServices 会为每一轮（含工具轮）各调用一次模型，
 * 每次 {@code onComplete} 都带回该轮用量，累加即得整任务总量。
 */
public final class MeteredChatModels {
    private MeteredChatModels() {}

    public static ChatLanguageModel blocking(ChatLanguageModel model, AgentMetrics metrics) {
        return new ChatLanguageModel() {
            @Override public Response<AiMessage> generate(List<ChatMessage> messages) {
                return call(metrics, () -> model.generate(messages));
            }

            @Override public Response<AiMessage> generate(List<ChatMessage> messages,
                                                          List<ToolSpecification> tools) {
                return call(metrics, () -> model.generate(messages, tools));
            }

            @Override public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification tool) {
                return call(metrics, () -> model.generate(messages, tool));
            }
        };
    }

    /** 失败的模型调用同样计数（只是拿不到用量），否则失败任务的 modelCalls 会被低估为 0。 */
    private static Response<AiMessage> call(AgentMetrics metrics,
                                            java.util.function.Supplier<Response<AiMessage>> invocation) {
        try {
            Response<AiMessage> response = invocation.get();
            record(metrics, response);
            return response;
        } catch (RuntimeException e) {
            metrics.modelCalled(null, null);
            throw e;
        }
    }

    public static StreamingChatLanguageModel streaming(StreamingChatLanguageModel model, AgentMetrics metrics) {
        return new StreamingChatLanguageModel() {
            @Override public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
                model.generate(messages, metered(handler, metrics));
            }

            @Override public void generate(List<ChatMessage> messages, List<ToolSpecification> tools,
                                           StreamingResponseHandler<AiMessage> handler) {
                model.generate(messages, tools, metered(handler, metrics));
            }

            @Override public void generate(List<ChatMessage> messages, ToolSpecification tool,
                                           StreamingResponseHandler<AiMessage> handler) {
                model.generate(messages, tool, metered(handler, metrics));
            }
        };
    }

    private static StreamingResponseHandler<AiMessage> metered(StreamingResponseHandler<AiMessage> handler,
                                                                AgentMetrics metrics) {
        return new StreamingResponseHandler<>() {
            private final AtomicBoolean recorded = new AtomicBoolean();

            @Override public void onNext(String token) {
                handler.onNext(token);
            }

            @Override public void onComplete(Response<AiMessage> response) {
                if (recorded.compareAndSet(false, true)) record(metrics, response);
                handler.onComplete(response);
            }

            @Override public void onError(Throwable error) {
                if (recorded.compareAndSet(false, true)) metrics.modelCalled(null, null);
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
