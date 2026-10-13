package cn.edu.seig.vibemusic.result;


import cn.edu.seig.vibemusic.constant.MessageConstant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 后端统一返回结果
 *
 * @param <T>
 */
@NoArgsConstructor
@AllArgsConstructor
@Data
public class Result<T> {

    /**
     * 「需要人工确认」业务码：HTTP 仍是 200，由前端按 {@code code} 分支处理，
     * 用于「先给清单、确认后再执行」的两段式写操作（如专辑上传时自动创建缺失歌手）。
     */
    public static final int CODE_CONFIRMATION_REQUIRED = 409;

    private Integer code;   // 业务状态码  0-成功  1-失败  409-需要人工确认
    private String message; // 提示信息
    private T data;         // 响应数据

    // 快速返回操作成功响应结果(默认提示信息)
    public static <T> Result<T> success(T data) {
        return new Result<>(0, MessageConstant.OPERATION + MessageConstant.SUCCESS, data);
    }

    // 快速返回操作成功响应结果(默认提示信息)
    public static Result success() {
        return new Result(0, MessageConstant.OPERATION + MessageConstant.SUCCESS, null);
    }

    // 快速返回操作失败响应结果(默认提示信息)
    public static Result error() {
        return new Result(1, MessageConstant.OPERATION + MessageConstant.FAILED, null);
    }

    // 快速返回操作成功响应结果(带响应数据和自定义提示信息)
    public static <T> Result<T> success(String message, T data) {
        return new Result<>(0, message, data);
    }

    // 快速返回操作成功响应结果(带自定义提示信息)
    public static Result success(String message) {
        return new Result(0, message, null);
    }

    // 快速返回操作失败响应结果(带自定义提示信息)
    public static Result error(String message) {
        return new Result(1, message, null);
    }

    /**
     * 返回「需要人工确认」：{@code code = 409}，并带上供前端展示的确认清单。
     * 调用方据此弹确认框，用户确认后再带确认标记重新提交。
     */
    public static <T> Result<T> confirmationRequired(String message, T data) {
        return new Result<>(CODE_CONFIRMATION_REQUIRED, message, data);
    }

}
