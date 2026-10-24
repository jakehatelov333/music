package cn.edu.seig.vibemusic.model.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 用量总览。
 *
 * <p><b>口径说明（界面必须原样展示）：</b>
 * <ul>
 *     <li>统计范围是「Agent 主对话任务」（{@code tb_ai_task_metric}），
 *         <b>不含</b>会话摘要与画像概述这类后台模型调用 —— 它们没有请求级 Trace，
 *         混进来会让"每请求平均 Token"失真；</li>
 *     <li>Token 不可获取的任务单独计数（{@code tokenUnavailable}），不当作 0；</li>
 *     <li>费用是按配置单价估算，不是供应商账单；未配置单价时为 null。</li>
 * </ul>
 */
@Data
public class AiOverviewVO {

    private String from;
    private String to;
    /** 本次统计覆盖范围的文字说明，直接展示给管理员。 */
    private String scope;

    private long totalRequests;
    private long successRequests;
    private long failedRequests;
    private long timeoutRequests;
    private long interruptedRequests;

    private Long inputTokens;
    private Long outputTokens;
    private Long totalTokens;
    /** Token 不可获取的请求数。 */
    private long tokenUnavailable;

    private long modelCalls;
    private long toolCalls;
    private long toolFailures;

    private Long avgLatencyMs;
    private Long p50LatencyMs;
    private Long p95LatencyMs;

    private long guardedRequests;
    private BigDecimal guardedRate;

    private AiCostVO cost;

    /** 按小时或按天的 Token 趋势（区分输入/输出）。 */
    private List<TrendPoint> trend;
    private String trendGranularity;

    private List<ModelStat> models;
    private List<UserStat> users;
    private List<ToolStat> tools;
    private List<FailureStat> failures;
    private List<SlowRequest> slowest;
    private List<TrendPoint> guardTrend;

    /** 追踪系统自身的健康度：采集失败次数（进程启动以来）。 */
    private long traceCaptureFailures;
    private boolean traceEnabled;
    private boolean traceContentCaptured;

    @Data
    public static class TrendPoint {
        private String bucket;
        private long requests;
        private long inputTokens;
        private long outputTokens;
        private long guarded;
    }

    @Data
    public static class ModelStat {
        private String model;
        private long requests;
        private long inputTokens;
        private long outputTokens;
        private Long avgLatencyMs;
        private BigDecimal failureRate;
        private BigDecimal cost;
        private boolean costConfigured;
    }

    @Data
    public static class UserStat {
        private Long userId;
        private String username;
        private long requests;
        private long totalTokens;
        private Long avgTokensPerRequest;
    }

    @Data
    public static class ToolStat {
        private String toolName;
        private long calls;
        private long failures;
        private BigDecimal successRate;
        private BigDecimal failureRate;
        private Long avgDurationMs;
    }

    @Data
    public static class FailureStat {
        private String category;
        private long count;
    }

    @Data
    public static class SlowRequest {
        private String traceId;
        private String model;
        private long latencyMs;
        private String status;
        private java.time.LocalDateTime startedAt;
        private Long userId;
        private String username;
    }
}
