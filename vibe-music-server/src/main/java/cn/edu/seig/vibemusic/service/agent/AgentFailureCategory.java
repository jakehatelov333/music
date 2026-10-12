package cn.edu.seig.vibemusic.service.agent;

/**
 * 任务失败分类。分开统计的意义在于：能一眼看出失败是“模型侧”“工具侧”
 * “业务侧”还是“客户端/传输侧”，而不是一个没有解释力的综合分。
 */
public final class AgentFailureCategory {
    private AgentFailureCategory() {}

    public static final String NONE = "NONE";
    /** 模型调用抛错或返回不可用。 */
    public static final String MODEL_ERROR = "MODEL_ERROR";
    /** 工具执行失败或达到调用上限。 */
    public static final String TOOL_ERROR = "TOOL_ERROR";
    /** 单次任务超时。 */
    public static final String TIMEOUT = "TIMEOUT";
    /** SSE 连接中断 / 页面离开 / 客户端主动取消。 */
    public static final String INTERRUPTED = "INTERRUPTED";
    /** 业务规则或数据库写入失败。 */
    public static final String BUSINESS_ERROR = "BUSINESS_ERROR";
    /** 其他未归类异常。 */
    public static final String UNKNOWN = "UNKNOWN";
}
