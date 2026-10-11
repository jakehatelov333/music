package cn.edu.seig.vibemusic.config;

import cn.edu.seig.vibemusic.memory.MysqlChatMemoryStore;
import cn.edu.seig.vibemusic.service.MusicAssistant;
import cn.edu.seig.vibemusic.service.MusicAssistantNoTools;
import cn.edu.seig.vibemusic.tool.MusicTools;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.dashscope.QwenChatModel;
import dev.langchain4j.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class LangChain4jConfig {

    @Value("${langchain4j.dashscope.api-key:}")
    private String apiKey;

    @Value("${langchain4j.dashscope.model-name}")
    private String modelName;

    private final ConcurrentHashMap<Object, MessageWindowChatMemory> chatMemoryCache = new ConcurrentHashMap<>();

    @Bean
    public QwenChatModel qwenChatModel() {
        return QwenChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .build();
    }

    @Bean
    public QwenStreamingChatModel qwenStreamingChatModel() {
        return QwenStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .build();
    }

    @Bean
    public ChatMemoryProvider chatMemoryProvider(MysqlChatMemoryStore store) {
        return memoryId -> chatMemoryCache.computeIfAbsent(memoryId, id ->
                MessageWindowChatMemory.builder()
                        .id(id)
                        .maxMessages(20)
                        .chatMemoryStore(store)
                        .build());
    }

    @Bean
    public MusicAssistant musicAssistant(QwenChatModel model,
                                         QwenStreamingChatModel streamingModel,
                                         ChatMemoryProvider chatMemoryProvider,
                                         MusicTools musicTools) {
        LoggingQwenChatModel loggingModel = new LoggingQwenChatModel(model);
        return AiServices.builder(MusicAssistant.class)
                .chatLanguageModel(loggingModel)
                .streamingChatLanguageModel(streamingModel)
                .chatMemoryProvider(chatMemoryProvider)
                .tools(musicTools)
                .build();
    }

    @Bean
    public MusicAssistantNoTools musicAssistantNoTools(QwenChatModel model,
                                                       ChatMemoryProvider chatMemoryProvider) {
        LoggingQwenChatModel loggingModel = new LoggingQwenChatModel(model);
        return AiServices.builder(MusicAssistantNoTools.class)
                .chatLanguageModel(loggingModel)
                .chatMemoryProvider(chatMemoryProvider)
                .build();
    }
}
