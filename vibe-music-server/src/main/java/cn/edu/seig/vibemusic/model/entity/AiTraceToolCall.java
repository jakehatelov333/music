package cn.edu.seig.vibemusic.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单次工具调用明细：把「模型提出的调用」与「后端真正执行」放在同一行。
 *
 * <p><b>两个 ID 为什么必须分开存：</b>{@code modelToolCallId} 来自供应商响应
 * （DashScope 返回的 {@code tool_call.id}），{@code serverToolCallId} 是服务端自己
 * 分配的关联 ID（同时是 SSE {@code toolCallId}）。两者格式、生成方都不同，
 * 不能假定相等。它们的关联由 {@code AgentTraceRecorder} 在执行器包装层用线程绑定
 * 建立 —— 是真实的「谁触发了谁」，不是按时间接近推断。
 *
 * <p>若模型侧 ID 为空，说明这次工具执行<b>不是由模型请求触发</b>的（例如单测直接调用
 * 工具方法），{@code snapshotNote} 会写明，界面不应把它显示成模型调用。
 */
@Data
public class AiTraceToolCall {

    private Long id;
    private String spanId;
    private String traceId;
    private String toolName;
    private String modelToolCallId;
    private String serverToolCallId;
    private String rawArguments;
    private String parsedArguments;
    private String status;
    private String resultCode;
    private Integer resultTotal;
    private String resultSummary;
    private String resultPayload;
    private String errorCategory;
    private Long durationMs;
    private String snapshotState;
    private String snapshotNote;
    private LocalDateTime createdAt;
}
