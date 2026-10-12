package cn.edu.seig.vibemusic.service.ai;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BoundedChatModels {
    private BoundedChatModels() {}

    public static ChatLanguageModel blocking(ChatLanguageModel model, AiRunBudget budget) {
        return new ChatLanguageModel() {
            @Override public Response<AiMessage> generate(List<ChatMessage> messages) {
                budget.beforeModel();
                Response<AiMessage> result = model.generate(messages);
                budget.beforeTools(result);
                return result;
            }
            @Override public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> tools) {
                budget.beforeModel();
                Response<AiMessage> result = model.generate(messages, tools);
                budget.beforeTools(result);
                return result;
            }
            @Override public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification tool) {
                budget.beforeModel();
                Response<AiMessage> result = model.generate(messages, tool);
                budget.beforeTools(result);
                return result;
            }
        };
    }

    public static StreamingChatLanguageModel streaming(StreamingChatLanguageModel model, AiRunBudget budget) {
        return new StreamingChatLanguageModel() {
            @Override public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
                budget.beforeModel();
                model.generate(messages, guarded(handler, budget));
            }
            @Override public void generate(List<ChatMessage> messages, List<ToolSpecification> tools,
                                           StreamingResponseHandler<AiMessage> handler) {
                budget.beforeModel();
                model.generate(messages, tools, guarded(handler, budget));
            }
            @Override public void generate(List<ChatMessage> messages, ToolSpecification tool,
                                           StreamingResponseHandler<AiMessage> handler) {
                budget.beforeModel();
                model.generate(messages, tool, guarded(handler, budget));
            }
        };
    }

    private static StreamingResponseHandler<AiMessage> guarded(StreamingResponseHandler<AiMessage> handler,
                                                               AiRunBudget budget) {
        return new StreamingResponseHandler<>() {
            private final AtomicBoolean finished = new AtomicBoolean();
            @Override public void onNext(String token) {
                if (finished.get()) return;
                try { budget.check(); handler.onNext(token); }
                catch (RuntimeException e) { onError(e); }
            }
            @Override public void onComplete(Response<AiMessage> response) {
                if (!finished.compareAndSet(false, true)) return;
                try {
                    // 在 AiServices 执行工具之前拦截整批调用，包括并行工具请求。
                    budget.beforeTools(response);
                    handler.onComplete(response);
                } catch (RuntimeException e) { handler.onError(e); }
            }
            @Override public void onError(Throwable error) {
                if (finished.compareAndSet(false, true)) handler.onError(error);
            }
        };
    }
}
