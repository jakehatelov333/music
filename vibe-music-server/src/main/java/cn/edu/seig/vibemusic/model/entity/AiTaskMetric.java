package cn.edu.seig.vibemusic.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单次 Agent 任务的执行指标。
 *
 * <p>所有字段都来自真实执行：模型调用次数与 Token 取自模型响应，
 * 工具次数与失败数取自工具适配层，业务结果取自数据库写入结果。
 *
 * <p>{@code tokenSource} 明确标注 Token 数据的来源：{@code PROVIDER} 表示供应商返回的实测值，
 * {@code UNAVAILABLE} 表示本次没有拿到用量（此时 {@code inputTokens/outputTokens} 为 null）。
 * 这样成本分析时不会把缺失当成 0。
 *
 * <p>本表<b>不记录</b>用户消息内容、模型回复内容、API Key 或任何凭据。
 */
@Data
public class AiTaskMetric {

    private Long id;
    private String taskId;
    private Long userId;
    private String chatId;
    private String model;
    private Integer modelCalls;
    private Integer toolCalls;
    private Integer toolFailures;
    private Long inputTokens;
    private Long outputTokens;
    private String tokenSource;
    private Integer resultCount;
    private Boolean success;
    private String failureCategory;
    private String failureMessage;
    /** 输出护栏是否命中：模型声称执行了写操作但服务端没有对应事实。 */
    private Boolean outputGuarded;
    private Long latencyMs;
    private Long playlistId;
    private LocalDateTime createdAt;
}
