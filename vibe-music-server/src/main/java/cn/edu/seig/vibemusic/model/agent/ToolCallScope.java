package cn.edu.seig.vibemusic.model.agent;

/**
 * 当前线程正在执行的工具调用 ID。
 *
 * <p><b>为什么需要它：</b>工具执行的真实入口是 LangChain4j 的 {@code ToolExecutor}，
 * 它拿得到模型给的 {@code ToolExecutionRequest}（含供应商侧 ID）；而工具方法内部
 * （{@code MusicTools#invoke}）自己生成的 {@code toolCallId} 是给 SSE 用的。
 * 两者必须一致，否则前端事件流与后端 Trace 无法对照，只能靠"时间接近"猜关联。
 *
 * <p>执行器包装层在执行前把 Trace 分配的 span ID 绑定到本线程，
 * {@link AgentContext#nextToolCallId(String)} 优先采用它；执行结束立即清除。
 * 工具执行在 LangChain4j 中是<b>顺序</b>的（同一响应里的多个工具请求逐个执行），
 * 因此线程绑定不会串号。
 */
public final class ToolCallScope {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private ToolCallScope() {
    }

    public static void bind(String toolCallId) {
        if (toolCallId == null) CURRENT.remove();
        else CURRENT.set(toolCallId);
    }

    public static String current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
