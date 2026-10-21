package cn.edu.seig.vibemusic.service.agent;

import cn.edu.seig.vibemusic.model.agent.AgentContext;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单次 Agent 任务的执行指标累加器。
 *
 * <p>所有数字都来自<b>真实执行</b>：模型调用次数与 Token 用量取自模型响应
 * （{@code Response#tokenUsage()}），工具次数与耗时取自工具适配层的实际计时，
 * 业务结果取自数据库写入结果。没有拿到精确用量时标记为
 * {@link #TOKEN_SOURCE_UNAVAILABLE}，绝不把估算值写成实测值。
 *
 * <p>线程安全：模型回调可能在其他线程触发，因此全部使用原子类型。
 */
public final class AgentMetrics implements AgentContext.AgentMetricsHolder {

    /** Token 用量来自模型供应商返回值，可视为实测。 */
    public static final String TOKEN_SOURCE_PROVIDER = "PROVIDER";
    /** 供应商未返回用量，或任务在返回前失败；此时 Token 计为不可获取。 */
    public static final String TOKEN_SOURCE_UNAVAILABLE = "UNAVAILABLE";

    private final String taskId;
    private final Long userId;
    private final String chatId;
    private final String model;
    private final long startedNanos = System.nanoTime();
    private final long startedMillis = System.currentTimeMillis();

    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AtomicInteger toolFailures = new AtomicInteger();
    private final AtomicInteger resultCount = new AtomicInteger();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicInteger usageSamples = new AtomicInteger();

    private volatile boolean finished;
    private volatile boolean success;
    private volatile String failureCategory;
    private volatile String failureMessage;
    private volatile Long playlistId;
    private volatile String pendingTaskId;
    private volatile boolean outputGuarded;
    private volatile boolean playedHistoryFiltered;
    private volatile long latencyMillis;

    public AgentMetrics(String taskId, Long userId, String chatId, String model) {
        this.taskId = taskId;
        this.userId = userId;
        this.chatId = chatId;
        this.model = model;
    }

    public void modelCalled(Integer input, Integer output) {
        modelCalls.incrementAndGet();
        if (input != null || output != null) {
            usageSamples.incrementAndGet();
            if (input != null) inputTokens.addAndGet(input);
            if (output != null) outputTokens.addAndGet(output);
        }
    }

    @Override
    public void toolCalled() {
        toolCalls.incrementAndGet();
    }

    @Override
    public void toolFailed() {
        toolFailures.incrementAndGet();
    }

    @Override
    public void resultsFound(int count) {
        if (count > 0) resultCount.addAndGet(count);
    }

    @Override
    public void pendingAction(String pendingTaskId) {
        if (pendingTaskId != null) this.pendingTaskId = pendingTaskId;
    }

    public String pendingTaskId() {
        return pendingTaskId;
    }

    /**
     * 输出护栏是否命中：模型声称「已生成预览/已创建成功」但本轮并无对应的服务端事实。
     * 这是衡量「模型有没有真的执行工具」的直接信号，用于统计工具调用正确性。
     */
    public void markOutputGuarded() {
        this.outputGuarded = true;
    }

    public boolean outputGuarded() {
        return outputGuarded;
    }

    /**
     * 本轮是否真的按播放历史做过排除。
     *
     * <p><b>当前没有任何工具会上报 true</b>：候选检索排除的是「本会话近期歌单已用过的歌」，
     * 与 {@code tb_play_history} 无关。这个标志位的存在是为了让输出护栏
     * <b>依赖事实而不是依赖"我记得没有这个能力"</b>——将来真的加了按播放历史排除的
     * 工具参数，只要在工具里调用本方法，护栏就会自动放行，不需要再改护栏代码。
     *
     * <p>默认 false 是保守选择：宁可多校正一次措辞，也不要把虚构的服务端行为放给用户。
     */
    public void markPlayedHistoryFiltered() {
        this.playedHistoryFiltered = true;
    }

    public boolean playedHistoryFiltered() {
        return playedHistoryFiltered;
    }

    public void finish(boolean success, String failureCategory, String failureMessage) {
        if (finished) return;
        this.latencyMillis = (System.nanoTime() - startedNanos) / 1_000_000L;
        this.success = success;
        this.failureCategory = failureCategory;
        this.failureMessage = failureMessage;
        this.finished = true;
    }

    public void playlistCreated(Long playlistId) {
        this.playlistId = playlistId;
    }

    public String taskId() { return taskId; }

    public Long userId() { return userId; }

    public String chatId() { return chatId; }

    public String model() { return model; }

    public long startedMillis() { return startedMillis; }

    public int modelCalls() { return modelCalls.get(); }

    public int toolCalls() { return toolCalls.get(); }

    public int toolFailures() { return toolFailures.get(); }

    public int resultCount() { return resultCount.get(); }

    public boolean success() { return success; }

    public String failureCategory() { return failureCategory; }

    public String failureMessage() { return failureMessage; }

    public Long playlistId() { return playlistId; }

    public long latencyMillis() { return latencyMillis; }

    public boolean finished() { return finished; }

    /** 是否拿到了供应商返回的真实用量。 */
    public boolean tokenUsageAvailable() {
        return usageSamples.get() > 0;
    }

    public String tokenSource() {
        return tokenUsageAvailable() ? TOKEN_SOURCE_PROVIDER : TOKEN_SOURCE_UNAVAILABLE;
    }

    public Long inputTokens() { return tokenUsageAvailable() ? inputTokens.get() : null; }

    public Long outputTokens() { return tokenUsageAvailable() ? outputTokens.get() : null; }

    /** 供 SSE {@code task} 事件使用的摘要，字段名与指标表保持一致。 */
    public java.util.Map<String, Object> summary() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("taskId", taskId);
        map.put("model", model);
        map.put("success", success);
        map.put("failureCategory", failureCategory);
        map.put("modelCalls", modelCalls());
        map.put("toolCalls", toolCalls());
        map.put("toolFailures", toolFailures());
        map.put("resultCount", resultCount());
        map.put("latencyMs", latencyMillis);
        map.put("inputTokens", inputTokens());
        map.put("outputTokens", outputTokens());
        map.put("tokenSource", tokenSource());
        map.put("playlistId", playlistId);
        // 确认流程是后续的独立请求，因此这里给出待确认任务 ID，
        // 便于从「对话任务」一路追到「待确认任务 → 真实歌单」。
        map.put("pendingTaskId", pendingTaskId);
        map.put("outputGuarded", outputGuarded);
        map.put("playedHistoryFiltered", playedHistoryFiltered);
        return map;
    }
}
