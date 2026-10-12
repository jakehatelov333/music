package cn.edu.seig.vibemusic.service.ai;

import cn.edu.seig.vibemusic.memory.MysqlChatMemoryStore;
import cn.edu.seig.vibemusic.service.MusicAssistant;
import cn.edu.seig.vibemusic.tool.MusicTools;
import dev.langchain4j.model.dashscope.QwenChatModel;
import dev.langchain4j.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Component
@RequiredArgsConstructor
public class AiAssistantFactory {
    private final QwenChatModel model;
    private final QwenStreamingChatModel streamingModel;
    private final MysqlChatMemoryStore store;
    private final MusicTools tools;
    @Value("${ai.timeout-seconds:60}") private int timeoutSeconds;
    @Value("${ai.max-tool-calls:4}") private int maxToolCalls;

    public Duration timeout() { return Duration.ofSeconds(timeoutSeconds); }

    public Run create(String chatId) {
        AiRunBudget budget = new AiRunBudget(timeout(), maxToolCalls);
        MysqlChatMemoryStore.Conversation memory = store.open(chatId, budget, timeout());
        try {
            MusicAssistant assistant = AiServices.builder(MusicAssistant.class)
                .chatLanguageModel(BoundedChatModels.blocking(model, budget))
                .streamingChatLanguageModel(BoundedChatModels.streaming(streamingModel, budget))
                .chatMemoryProvider(id -> {
                    if (!chatId.equals(id)) throw new IllegalArgumentException("会话身份不匹配");
                    return memory;
                })
                .tools(tools).build();
            return new Run(assistant, budget, memory::close);
        } catch (RuntimeException error) {
            memory.close();
            throw error;
        }
    }

    public record Run(MusicAssistant assistant, AiRunBudget budget, Runnable release) implements AutoCloseable {
        public Run(MusicAssistant assistant, AiRunBudget budget) { this(assistant, budget, () -> {}); }
        @Override public void close() {
            budget.close();
            release.run();
        }
    }
}
