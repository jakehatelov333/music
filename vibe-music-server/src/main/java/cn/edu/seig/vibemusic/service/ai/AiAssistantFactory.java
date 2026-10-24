package cn.edu.seig.vibemusic.service.ai;

import cn.edu.seig.vibemusic.memory.MysqlChatMemoryStore;
import cn.edu.seig.vibemusic.model.agent.AgentContext;
import cn.edu.seig.vibemusic.model.agent.AgentEvent;
import cn.edu.seig.vibemusic.model.agent.AgentEventSink;
import cn.edu.seig.vibemusic.service.MusicAssistant;
import cn.edu.seig.vibemusic.service.agent.AgentMetrics;
import cn.edu.seig.vibemusic.service.agent.trace.AgentTrace;
import cn.edu.seig.vibemusic.service.agent.trace.AgentTraceRecorder;
import cn.edu.seig.vibemusic.service.agent.trace.MeteredChatModels;
import cn.edu.seig.vibemusic.service.agent.trace.TracedToolExecutors;
import cn.edu.seig.vibemusic.tool.MusicTools;
import cn.edu.seig.vibemusic.tool.PlaylistActionTools;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.dashscope.QwenChatModel;
import dev.langchain4j.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 每次请求装配一个独立的 Agent 运行实例。
 *
 * <p>这里做五件事：
 * <ol>
 *     <li><b>可信上下文</b>：把服务端解析出的 {@code userId}、会话 {@code chatId} 与本次
 *         {@code taskId} 打包成 {@link AgentContext}，绑定到<b>本次专用的工具实例</b>上。
 *         工具方法签名里没有用户标识，模型无法伪造身份；</li>
 *     <li><b>指标采集</b>：模型外面套一层 {@link MeteredChatModels}，把每次调用的
 *         次数与 Token 用量记进 {@link AgentMetrics}（真实用量，不是估算）；</li>
 *     <li><b>Trace 采集</b>：同一层包装同时产出逐次调用的输入/输出/工具定义快照，
 *         工具执行器换成 {@link TracedToolExecutors} 以拿到模型侧原始 Tool Call；</li>
 *     <li><b>事件出口</b>：把 SSE 出口作为 {@link AgentEventSink} 注入上下文，
 *         工具层因此可以直接上报"开始调用/调用结束/结构化结果/等待确认"；</li>
 *     <li><b>系统提示词</b>：通过 {@link AgentSystemPrompt} 在运行时组装
 *         （基础提示词 + 用户音乐偏好），并把实际拼出的内容记进 Trace。</li>
 * </ol>
 *
 * <p>工具实例通过 {@code withContext} 生成新对象，Spring 单例 bean 始终是无上下文的，
 * 因此并发请求之间不会串上下文。
 *
 * <p><b>提示词组装时机：</b>{@code systemMessageProvider} 由 LangChain4j 在<b>每次</b>
 * 模型调用前调用，因此这里只组装一次并缓存，既避免重复读库，也保证
 * "Trace 里记录的那份提示词"与"真正发给模型的提示词"是同一个字符串。
 */
@Component
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class AiAssistantFactory {

    private final QwenChatModel model;
    private final QwenStreamingChatModel streamingModel;
    private final MysqlChatMemoryStore store;
    private final MusicTools readTools;
    private final PlaylistActionTools actionTools;
    /**
     * 运行时组装系统提示词。评测基座注入 {@code StaticSystemPrompt}，
     * 保证离线评测用例的行为与引入个性化之前完全一致。
     */
    private final AgentSystemPrompt systemPrompt;
    /**
     * Trace 采集器。允许为 {@code null}（评测与单元测试直接构造工厂时不关心追踪），
     * 此时所有采集调用都会安全跳过，业务行为完全不变。
     */
    private final AgentTraceRecorder traceRecorder;

    @Value("${ai.timeout-seconds:60}") private int timeoutSeconds;
    @Value("${ai.max-tool-calls:4}") private int maxToolCalls;
    @Value("${langchain4j.dashscope.model-name:unknown}") private String modelName;

    /**
     * 不带追踪的构造器：既有测试与评测基座直接 new 本类时不关心 Trace。
     *
     * <p>保留它而不是让所有调用点传 {@code null}，是为了让"是否采集追踪"成为
     * 一个显式选择 —— 测试默认不采集，生产由 Spring 注入真实采集器。
     */
    public AiAssistantFactory(QwenChatModel model, QwenStreamingChatModel streamingModel,
                              MysqlChatMemoryStore store, MusicTools readTools,
                              PlaylistActionTools actionTools, AgentSystemPrompt systemPrompt) {
        this(model, streamingModel, store, readTools, actionTools, systemPrompt, null);
    }

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
        AgentTrace trace = traceRecorder == null ? null : traceRecorder.begin(taskId, chatId, userId, modelName);
        MysqlChatMemoryStore.Conversation memory = store.open(chatId, budget, timeout());
        try {
            MusicTools boundReadTools = readTools.withContext(context);
            PlaylistActionTools boundActionTools = actionTools.withContext(context);

            // 提示词只组装一次并缓存：provider 会在每次模型调用前被调用，
            // 重复组装既浪费读库，也会让"记录的提示词"与"实际发出的提示词"出现两份。
            String[] promptCache = new String[1];
            String prompt = prompt(userId, chatId, trace, promptCache);

            // 工具执行器由我们提供，从而拿到模型侧的原始 Tool Call 请求。
            Map<ToolSpecification, ToolExecutor> executors = new LinkedHashMap<>();
            executors.putAll(TracedToolExecutors.wrap(boundReadTools, trace, traceRecorder));
            executors.putAll(TracedToolExecutors.wrap(boundActionTools, trace, traceRecorder));

            MusicAssistant assistant = AiServices.builder(MusicAssistant.class)
                .chatLanguageModel(MeteredChatModels.blocking(BoundedChatModels.blocking(model, budget),
                        metrics, trace, traceRecorder))
                .streamingChatLanguageModel(MeteredChatModels.streaming(
                        BoundedChatModels.streaming(streamingModel, budget), metrics, trace, traceRecorder))
                // 动态系统提示词：基础提示词 + 用户音乐偏好（见 AgentSystemPrompt）。
                // 闭包捕获本次运行的 userId/chatId —— 每个 AiServices 实例一份，
                // 并发请求之间天然隔离，不依赖 ThreadLocal。
                // 注意 MusicAssistant 上的 @SystemMessage 必须保持删除状态，
                // 否则 LangChain4j 会优先用注解，这里的 provider 静默失效。
                .systemMessageProvider(id -> promptCache[0] != null ? promptCache[0]
                        : prompt(userId, chatId, trace, promptCache))
                .chatMemoryProvider(id -> {
                    if (!chatId.equals(id)) throw new IllegalArgumentException("会话身份不匹配");
                    return memory;
                })
                .tools(executors)
                .build();
            return new Run(assistant, budget, metrics, taskId, sink, memory::close, trace);
        } catch (RuntimeException error) {
            memory.close();
            throw error;
        }
    }

    /**
     * 组装提示词并记入 Trace（只记一次）。
     *
     * <p>无论注入是否成功，记录的 {@code prompt} 都是<b>真正交给模型的字符串</b>；
     * 每个动态块的状态来自 {@link AgentSystemPrompt#assemble} 的实测结果。
     */
    private String prompt(Long userId, String chatId, AgentTrace trace, String[] cache) {
        AgentSystemPrompt.Assembly assembly;
        try {
            assembly = systemPrompt.assemble(userId, chatId);
        } catch (RuntimeException e) {
            // 组装整体失败时退回基础提示词，绝不让个性化故障阻断对话。
            assembly = new AgentSystemPrompt.Assembly(MusicAssistant.SYSTEM_PROMPT,
                    Map.of("base", "INJECTED", "assembly", "FAILED"));
        }
        cache[0] = assembly.prompt();
        if (trace != null && traceRecorder != null) {
            String base = MusicAssistant.SYSTEM_PROMPT;
            String hash = cn.edu.seig.vibemusic.service.agent.trace.TraceSnapshotBuilder.hash(base);
            String version = hash == null ? null : hash.substring(0, 12);
            traceRecorder.contextBuilt(trace, assembly.prompt(), base, version, hash, assembly.blocks());
        }
        return cache[0];
    }

    public record Run(MusicAssistant assistant, AiRunBudget budget, AgentMetrics metrics, String taskId,
                      AgentEventSink events, Runnable release, AgentTrace trace) implements AutoCloseable {
        /** 测试/无指标场景的构造器：仍然给出一个可用的指标对象，避免调用方到处判空。 */
        public Run(MusicAssistant assistant, AiRunBudget budget) {
            this(assistant, budget, new AgentMetrics("test-run", null, "test-chat", "unknown"), null,
                    AgentEventSink.NOOP, () -> { }, null);
        }

        /** 任务开始事件；由传输层在流开始处调用，保证事件顺序为 start → tool/token → task → done。 */
        public void announce() {
            // chatId 一并下发：新建会话时客户端正是靠它认领刚被创建的那段对话。
            if (metrics != null) events.emit(AgentEvent.start(taskId, metrics.model(), metrics.chatId()));
        }

        @Override public void close() {
            budget.close();
            release.run();
        }
    }
}
