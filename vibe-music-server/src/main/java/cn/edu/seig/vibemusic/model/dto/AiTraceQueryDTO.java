package cn.edu.seig.vibemusic.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Trace 检索条件。
 *
 * <p>全部字段可选，服务端组合成 AND 条件。<b>所有值都走 MyBatis 参数绑定</b>，
 * 不做字符串拼接；排序字段经白名单映射。
 *
 * <p>时间区间有硬上限（见 {@code AiTraceQueryService}），避免一次扫描整张表。
 */
@Data
public class AiTraceQueryDTO {

    /** 起始时间（含）。为空表示不限制下界。 */
    private LocalDateTime from;
    /** 结束时间（不含）。为空表示不限制上界。 */
    private LocalDateTime to;

    private Long userId;
    /** 用户账号模糊匹配；由后端按 tb_user.username 查询，不在前端加载用户列表。 */
    private String username;

    private String traceId;
    private String chatId;

    /** SUCCESS / FAILED / TIMEOUT / INTERRUPTED / MODEL_ERROR / TOOL_ERROR / BUSINESS_ERROR / UNKNOWN */
    private String status;
    private String model;
    private String toolName;
    private String failureCategory;

    private Long minInputTokens;
    private Long maxInputTokens;
    private Long minOutputTokens;
    private Long maxOutputTokens;
    private Long minTotalTokens;
    private Long maxTotalTokens;
    private Long minLatencyMs;

    /** true=只查调用过工具的；false=只查没调用过工具的；null=不限。 */
    private Boolean usedTools;
    /** true=只查命中输出护栏的。 */
    private Boolean outputGuarded;
    /** true=只查 Token 可获取的；false=只查不可获取的。 */
    private Boolean tokenAvailable;

    /** 排序字段（白名单）：startedAt / latencyMs / totalTokens / toolCalls / modelCalls。 */
    private String sortField;
    /** asc / desc，默认 desc。 */
    private String order;

    private Integer pageNum = 1;
    private Integer pageSize = 20;
}
