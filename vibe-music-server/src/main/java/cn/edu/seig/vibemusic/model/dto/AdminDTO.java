package cn.edu.seig.vibemusic.model.dto;

import cn.edu.seig.vibemusic.constant.MessageConstant;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 管理员注册 / 登录请求体。
 *
 * <p><b>取舍：</b>该 DTO 同时被 {@code AdminController} 的注册与登录两个接口复用，
 * 因此密码字段只做非空校验，不施加强度约束 —— 否则会挡住密码不合规的老管理员登录。
 * 管理员注册的密码强度校验留待把注册/登录 DTO 拆分后补齐。
 */
@Data
public class AdminDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 管理员用户名
     * 用户名格式：4-16位字符（字母、数字、下划线、连字符）
     */
    @NotBlank(message = MessageConstant.USERNAME + MessageConstant.NOT_NULL)
    @Pattern(regexp = "^[a-zA-Z0-9_-]{4,16}$", message = MessageConstant.USERNAME + MessageConstant.FORMAT_ERROR)
    private String username;

    /**
     * 管理员密码
     * 注册与登录复用本 DTO，故不施加强度约束（见类注释）。
     */
    @NotBlank(message = MessageConstant.PASSWORD + MessageConstant.NOT_NULL)
    private String password;

}
