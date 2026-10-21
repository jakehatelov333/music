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
    /**
     * 采样温度。刻意压低到 0.3 而不是用供应商默认值：
     * 助手的主要工作是"如实转述服务端事实 + 简短判断"，不需要创作性发散。
     * 实测同一段对话里，同样的工具返回（一字不差）两轮给出了两种语气，
     * 其中一轮还把"模型自行筛选"说成了"系统已过滤"——温度越高，这种措辞漂移越明显。
     * 需要更活泼的文案时应改提示词，而不是靠提高温度碰运气。
     */
    @Value("${ai.temperature:0.3}") private double temperature;

    @Bean
    public QwenChatModel qwenChatModel() {
        return QwenChatModel.builder().apiKey(apiKey).modelName(modelName)
                .maxTokens(maxOutputTokens).temperature((float) temperature).build();
    }

    @Bean
    public QwenStreamingChatModel qwenStreamingChatModel() {
        return QwenStreamingChatModel.builder().apiKey(apiKey).modelName(modelName)
                .maxTokens(maxOutputTokens).temperature((float) temperature).build();
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
