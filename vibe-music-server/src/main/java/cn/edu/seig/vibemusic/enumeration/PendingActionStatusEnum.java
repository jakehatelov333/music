package cn.edu.seig.vibemusic.enumeration;

/**
 * 待确认任务的状态机。
 *
 * <pre>
 *   PENDING ──claim──▶ EXECUTING ──成功──▶ SUCCEEDED
 *      │                   │
 *      │                   └──失败──▶ FAILED
 *      ├──用户取消──▶ CANCELLED
 *      └──超时────▶ EXPIRED
 * </pre>
 *
 * <p>只有 PENDING（或已僵死的 EXECUTING）可以被 claim 成 EXECUTING；
 * SUCCEEDED / FAILED 是终态，用于幂等重放时返回已保存的结果。
 */
public enum PendingActionStatusEnum {
    /** 等待用户确认，此时绝不写业务数据。 */
    PENDING("待确认"),
    /** 已抢占，正在执行。 */
    EXECUTING("执行中"),
    /** 执行成功，结果已持久化。 */
    SUCCEEDED("成功"),
    /** 执行失败，失败原因已持久化。 */
    FAILED("失败"),
    /** 用户主动取消。 */
    CANCELLED("已取消"),
    /** 超过有效期。 */
    EXPIRED("已过期");

    private final String description;

    PendingActionStatusEnum(String description) {
        this.description = description;
    }

    public String getStatus() {
        return name();
    }

    public String getDescription() {
        return description;
    }

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == EXPIRED;
    }
}
