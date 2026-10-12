package cn.edu.seig.vibemusic.config;

import dev.langchain4j.model.dashscope.QwenChatModel;
import dev.langchain4j.model.dashscope.QwenStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.*;

@Configuration
public class LangChain4jConfig {
    @Value("${langchain4j.dashscope.api-key:}") private String apiKey;
    @Value("${langchain4j.dashscope.model-name}") private String modelName;
    @Value("${ai.max-output-tokens:512}") private int maxOutputTokens;

    @Bean
    public QwenChatModel qwenChatModel() {
        return QwenChatModel.builder().apiKey(apiKey).modelName(modelName).maxTokens(maxOutputTokens).build();
    }

    @Bean
    public QwenStreamingChatModel qwenStreamingChatModel() {
        return QwenStreamingChatModel.builder().apiKey(apiKey).modelName(modelName).maxTokens(maxOutputTokens).build();
    }

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService aiExecutor() {
        return new ThreadPoolExecutor(4, 8, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService aiTimer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }
}
