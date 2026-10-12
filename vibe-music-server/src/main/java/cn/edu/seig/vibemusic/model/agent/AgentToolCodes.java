package cn.edu.seig.vibemusic.model.agent;

/**
 * Agent 工具结果的错误码集合。全部为稳定字符串，可被前端与评测集直接断言。
 */
public final class AgentToolCodes {
    private AgentToolCodes() {}

    /** 执行成功且有数据。 */
    public static final String OK = "OK";
    /** 执行成功但没有命中任何数据（不等价于失败）。 */
    public static final String EMPTY = "EMPTY";
    /** 参数缺失或不合法，模型可以修正后重试。 */
    public static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";
    /** 需要登录（游客调用了写工具）。 */
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    /** 业务规则拒绝，例如歌曲数量超出上限、歌单数量超限。 */
    public static final String REJECTED = "REJECTED";
    /** 部分参数无效，已按有效部分继续或整体拒绝。 */
    public static final String PARTIAL = "PARTIAL";
    /** 目标资源不存在。 */
    public static final String NOT_FOUND = "NOT_FOUND";
    /** 工具内部异常。 */
    public static final String ERROR = "ERROR";
}
