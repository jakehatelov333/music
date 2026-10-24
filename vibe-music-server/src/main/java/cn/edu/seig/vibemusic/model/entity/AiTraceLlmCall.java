package cn.edu.seig.vibemusic.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单次模型调用明细。
 *
 * <p><b>为什么必须按调用存，而不是只存任务合计：</b>ReAct 循环里每一轮的输入都不一样
 * （第 1 轮是「系统提示词 + 历史 + 用户问题」，第 2 轮还多了「模型自己的工具调用请求 +
 * 工具返回结果」）。排查「模型为什么在第 2 轮忽略了工具结果」时，只有逐轮的真实输入
 * 才能回答，任务级合计做不到。
 *
 * <p><b>数据来源：</b>全部来自 {@code MeteredChatModels} 包装的真实调用入口 ——
 * {@code messages} 是真正传给模型的列表，{@code systemMessage} 是从该列表中取出的
 * SYSTEM 消息，不是按模板重新拼的。
 *
 * <p>{@code snapshotState} 明确标注快照是否完整：{@code FULL} / {@code TRUNCATED}
 * （超长被截断）/ {@code REDACTED}（命中脱敏规则）/ {@code DISABLED}（关闭了内容采集）。
 * 界面必须把 {@code snapshotNote} 一并展示，不能把截断内容当作完整原文。
 */
@Data
public class AiTraceLlmCall {

    private Long id;
    private String spanId;
    private String traceId;
    private Integer callIndex;
    private String messagesJson;
    private String systemMessage;
    private String promptHash;
    private String toolsJson;
    private String toolsHash;
    private String outputText;
    private String toolRequestsJson;
    private String finishReason;
    private String snapshotState;
    private String snapshotNote;
    private LocalDateTime createdAt;
}
