package cn.edu.seig.vibemusic.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单次模型调用详情。
 *
 * <p><b>字段全部来自真实调用入口</b>（{@code MeteredChatModels}）：
 * {@code messages} 是当时真正传给模型的列表，{@code systemMessage} 从该列表中取出，
 * 不是按模板重建的。
 *
 * <p>{@code snapshotState} / {@code snapshotNote} 必须原样展示：截断或关闭采集时，
 * 界面不能把内容说成完整原文。
 */
@Data
public class AiLlmCallVO {

    private String spanId;
    private Integer callIndex;
    private String model;
    private Long inputTokens;
    private Long outputTokens;
    private String tokenSource;
    private Long durationMs;
    private String status;
    private String errorCategory;
    private String errorMessage;

    private String systemMessage;
    private String promptHash;
    private String promptVersion;
    /** 实际生效的完整系统提示词（基础 + 动态块）。 */
    private String effectivePrompt;
    /** 基础提示词原文（按版本还原）。 */
    private String basePrompt;
    /** 动态上下文块的注入状态。 */
    private Object blocks;

    /** 真实输入消息列表（已脱敏/截断）。 */
    private Object messages;
    /** 模型可见的工具定义（含参数 Schema）。 */
    private Object tools;
    private String outputText;
    /** 模型提出的工具调用请求（名称 + 原始参数 + 模型侧 ID）。 */
    private Object toolRequests;
    private String finishReason;

    private String snapshotState;
    private String snapshotNote;
    private LocalDateTime createdAt;
}
