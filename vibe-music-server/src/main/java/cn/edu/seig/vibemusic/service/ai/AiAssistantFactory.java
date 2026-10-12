package cn.edu.seig.vibemusic.service.ai;

import cn.edu.seig.vibemusic.memory.MysqlChatMemoryStore;
import cn.edu.seig.vibemusic.model.agent.AgentContext;
import cn.edu.seig.vibemusic.model.agent.AgentEvent;
import cn.edu.seig.vibemusic.model.agent.AgentEventSink;
import cn.edu.seig.vibemusic.service.MusicAssistant;
import cn.edu.seig.vibemusic.service.agent.AgentMetrics;
import cn.edu.seig.vibemusic.service.agent.MeteredChatModels;
import cn.edu.seig.vibemusic.tool.MusicTools;
import cn.edu.seig.vibemusic.tool.PlaylistActionTools;
import dev.langchain4j.model.dashscope.QwenChatModel;
import dev.langchain4j.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * 每次请求装配一个独立的 Agent 运行实例。
 *
 * <p>这里做三件事，都是第 9 项的核心：
 * <ol>
 *     <li><b>可信上下文</b>：把服务端解析出的 {@code userId}、会话 {@code chatId} 与本次
 *         {@code taskId} 打包成 {@link AgentContext}，绑定到<b>本次专用的工具实例</b>上。
 *         工具方法签名里没有用户标识，模型无法伪造身份；</li>
 *     <li><b>指标采集</b>：模型外面套一层 {@link MeteredChatModels}，把每次调用的
 *         次数与 Token 用量记进 {@link AgentMetrics}（真实用量，不是估算）；</li>
 *     <li><b>事件出口</b>：把 SSE 出口作为 {@link AgentEventSink} 注入上下文，
 *         工具层因此可以直接上报“开始调用/调用结束/结构化结果/等待确认”。</li>
 * </ol>
 *
 * <p>工具实例通过 {@code withContext} 生成新对象，Spring 单例 bean 始终是无上下文的，
 * 因此并发请求之间不会串上下文。
 */
@Component
@RequiredArgsConstructor
public class AiAssistantFactory {

    private final QwenChatModel model;
    private final QwenStreamingChatModel streamingModel;
    private final MysqlChatMemoryStore store;
    private final MusicTools readTools;
    private final PlaylistActionTools actionTools;

    @Value("${ai.timeout-seconds:60}") private int timeoutSeconds;
    @Value("${ai.max-tool-calls:4}") private int maxToolCalls;
    @Value("${langchain4j.dashscope.model-name:unknown}") private String modelName;

    public Duration timeout() {
        return Duration.ofSeconds(timeoutSeconds);
    }

    /** 无上下文运行（评测与后台任务用）。 */
    public Run create(String chatId) {
        return create(chatId, null, AgentEventSink.NOOP);
    }

    public Run create(String chatId, Long userId, AgentEventSink events) {
        String taskId = UUID.randomUUID().toString();
        AgentMetrics metrics = new AgentMetrics(taskId, userId, chatId, modelName);
        AgentEventSink sink = events == null ? AgentEventSink.NOOP : events;
        AgentContext context = AgentContext.of(userId, chatId, taskId, sink).withMetrics(metrics);
        AiRunBudget budget = new AiRunBudget(timeout(), maxToolCalls);
        MysqlChatMemoryStore.Conversation memory = store.open(chatId, budget, timeout());
        try {
            MusicAssistant assistant = AiServices.builder(MusicAssistant.class)
                .chatLanguageModel(MeteredChatModels.blocking(BoundedChatModels.blocking(model, budget), metrics))
                .streamingChatLanguageModel(MeteredChatModels.streaming(
                        BoundedChatModels.streaming(streamingModel, budget), metrics))
                .chatMemoryProvider(id -> {
                    if (!chatId.equals(id)) throw new IllegalArgumentException("会话身份不匹配");
                    return memory;
                })
                // 每次运行装配专属工具实例，上下文随实例走。
                .tools(readTools.withContext(context), actionTools.withContext(context))
                .build();
            return new Run(assistant, budget, metrics, taskId, sink, memory::close);
        } catch (RuntimeException error) {
            memory.close();
            throw error;
        }
    }

    public record Run(MusicAssistant assistant, AiRunBudget budget, AgentMetrics metrics, String taskId,
                      AgentEventSink events, Runnable release) implements AutoCloseable {
        /** 测试/无指标场景的构造器：仍然给出一个可用的指标对象，避免调用方到处判空。 */
        public Run(MusicAssistant assistant, AiRunBudget budget) {
            this(assistant, budget, new AgentMetrics("test-run", null, "test-chat", "unknown"), null,
                    AgentEventSink.NOOP, () -> { });
        }

        /** 任务开始事件；由传输层在流开始处调用，保证事件顺序为 start → tool/token → task → done。 */
        public void announce() {
            if (metrics != null) events.emit(AgentEvent.start(taskId, metrics.model()));
        }

        @Override public void close() {
            budget.close();
            release.run();
        }
    }
}
