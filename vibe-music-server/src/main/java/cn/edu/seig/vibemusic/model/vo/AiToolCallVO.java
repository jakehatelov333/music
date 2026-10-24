package cn.edu.seig.vibemusic.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单次工具调用详情：模型请求 ↔ 后端执行的关联结果。
 *
 * <p>{@code modelToolCallId} 与 {@code serverToolCallId} 是两个独立的 ID：
 * 前者来自供应商响应，后者是服务端分配（同时是 SSE toolCallId）。
 * 二者的关联由执行器包装层建立，不是按时间接近推断。
 */
@Data
public class AiToolCallVO {

    private String spanId;
    private String toolName;
    private String modelToolCallId;
    private String serverToolCallId;
    private String rawArguments;
    private Object parsedArguments;
    private String status;
    private String resultCode;
    private Integer resultTotal;
    private String resultSummary;
    private Object resultPayload;
    private String errorCategory;
    private Long durationMs;
    private String snapshotState;
    private String snapshotNote;
    private LocalDateTime createdAt;
}
