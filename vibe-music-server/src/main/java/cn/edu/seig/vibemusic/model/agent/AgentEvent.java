package cn.edu.seig.vibemusic.model.agent;

/**
 * Agent 任务的流式事件。一个事件 = 一条 SSE 消息，{@code type} 直接作为 SSE 的 event 名。
 *
 * <p>同一任务的多个事件通过 {@code taskId} 关联；同一工具调用的开始/结束/结构化数据
 * 通过 {@code toolCallId} 关联。前端据此把“过程状态”和“最终结果”分开渲染。
 *
 * <p>字段全部可空，按 {@code type} 取值：
 * <table>
 *     <tr><th>type</th><th>含义</th><th>使用字段</th></tr>
 *     <tr><td>start</td><td>Agent 开始处理</td><td>taskId, message</td></tr>
 *     <tr><td>tool</td><td>工具调用开始/结束</td><td>toolCallId, tool, phase, ok, code, message, total, durationMs</td></tr>
 *     <tr><td>songs</td><td>结构化歌曲数据</td><td>toolCallId, kind, total, data</td></tr>
 *     <tr><td>confirm</td><td>等待用户确认</td><td>toolCallId, data（待确认任务预览）</td></tr>
 *     <tr><td>task</td><td>任务结束汇总（成功/失败/取消）</td><td>ok, code, message, data（指标摘要）</td></tr>
 *     <tr><td>token / done / error</td><td>沿用既有协议，格式不变</td><td>data 为纯文本</td></tr>
 * </table>
 */
public record AgentEvent(
        String type,
        String taskId,
        String toolCallId,
        String tool,
        String phase,
        Boolean ok,
        String code,
        String message,
        Integer total,
        String kind,
        Object data,
        Long durationMs,
        SongSearch search
) {
    public AgentEvent(String type, String taskId, String toolCallId, String tool, String phase,
                      Boolean ok, String code, String message, Integer total, String kind,
                      Object data, Long durationMs) {
        this(type, taskId, toolCallId, tool, phase, ok, code, message, total, kind, data, durationMs, null);
    }
    public static final String START = "start";
    public static final String TOOL = "tool";
    public static final String SONGS = "songs";
    public static final String CONFIRM = "confirm";
    public static final String TASK = "task";
    public static final String CANCELLED = "cancelled";

    public static final String PHASE_START = "start";
    public static final String PHASE_END = "end";

    public static AgentEvent start(String taskId, String model) {
        return new AgentEvent(START, taskId, null, null, null, null, null,
                "Agent 开始处理", null, null, java.util.Map.of("model", model == null ? "" : model), null);
    }

    public static AgentEvent toolStart(String taskId, String toolCallId, String tool, String label) {
        return new AgentEvent(TOOL, taskId, toolCallId, tool, PHASE_START, null, null, label, null, null, null, null);
    }

    public static AgentEvent toolEnd(String taskId, String toolCallId, String tool, ToolResult<?> result,
                                    long durationMs) {
        return new AgentEvent(TOOL, taskId, toolCallId, tool, PHASE_END, result.ok(), result.code(),
                result.message(), result.total(), result.kind(), null, durationMs);
    }

    public static AgentEvent songs(String taskId, String toolCallId, ToolResult<?> result) {
        return new AgentEvent(SONGS, taskId, toolCallId, null, null, result.ok(), result.code(),
                result.message(), result.total(), result.kind(), result.data(), null, result.search());
    }

    /** 待确认动作：payload 为 {@code PendingActionVO}，前端据此渲染确认卡片。 */
    public static AgentEvent confirm(String taskId, String toolCallId, Object preview) {
        return new AgentEvent(CONFIRM, taskId, toolCallId, null, null, true, AgentToolCodes.OK,
                "等待用户确认", null, AgentToolKinds.PLAYLIST_PREVIEW, preview, null);
    }

    public static AgentEvent task(String taskId, boolean ok, String code, String message, Object summary) {
        return new AgentEvent(TASK, taskId, null, null, null, ok, code, message, null, null, summary, null);
    }

    public static AgentEvent cancelled(String taskId, String message) {
        return new AgentEvent(CANCELLED, taskId, null, null, null, false, "CANCELLED", message, null, null, null, null);
    }
}
