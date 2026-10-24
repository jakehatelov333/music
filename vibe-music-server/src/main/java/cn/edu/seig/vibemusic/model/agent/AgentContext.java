package cn.edu.seig.vibemusic.model.agent;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单次 Agent 运行的可信上下文。
 *
 * <p><b>为什么是显式对象而不是 ThreadLocal：</b>模型回调与工具执行可能落在不同线程上，
 * ThreadLocal 在异步链路里会串用或丢失。这里把上下文做成<b>每次运行一份的不可变对象</b>，
 * 并随工具实例一起构造（见 {@code MusicTools#withContext}），因此并发请求之间天然隔离。
 *
 * <p><b>可信性：</b>{@code userId} 只能来自服务端认证（JWT → ThreadLocal → 此处），
 * 绝不由模型或前端传入。工具方法签名里没有任何用户标识参数，模型无法伪造身份。
 */
public record AgentContext(
        Long userId,
        String chatId,
        String taskId,
        AgentEventSink events,
        AgentMetricsHolder metrics,
        AtomicInteger toolSequence
) {
    /** 占位实现，避免 model 层反向依赖 service 层的指标类。 */
    public interface AgentMetricsHolder {
        void toolCalled();

        void toolFailed();

        void resultsFound(int count);

        /** 本次任务生成的待确认任务 ID，用于把「对话任务」与「后续确认/歌单」串起来。 */
        void pendingAction(String pendingTaskId);
    }

    public static AgentContext none() {
        return new AgentContext(null, null, null, AgentEventSink.NOOP, null, new AtomicInteger());
    }

    public static AgentContext of(Long userId, String chatId, String taskId, AgentEventSink events) {
        return new AgentContext(userId, chatId, taskId,
                events == null ? AgentEventSink.NOOP : events, null, new AtomicInteger());
    }

    public AgentContext withMetrics(AgentMetricsHolder value) {
        return new AgentContext(userId, chatId, taskId, events, value, toolSequence);
    }

    public boolean authenticated() {
        return userId != null;
    }

    public void emit(AgentEvent event) {
        events.emit(event);
    }

    /**
     * 每次工具调用分配一个稳定的关联 ID，用于把 start / end / 结构化数据串起来。
     *
     * <p>若追踪层（{@code TracedToolExecutors}）已在当前线程绑定了 ID，则直接采用它 ——
     * 这样 SSE 的 {@code toolCallId} 与后端 Trace 的 {@code span_id} 是同一个值，
     * 前端事件流和落库记录可以精确对照，而不是靠时间接近推断。
     */
    public String nextToolCallId(String toolName) {
        String bound = ToolCallScope.current();
        if (bound != null) return bound;
        return (taskId == null ? "run" : taskId) + "-" + toolName + "-" + toolSequence.incrementAndGet();
    }
}
