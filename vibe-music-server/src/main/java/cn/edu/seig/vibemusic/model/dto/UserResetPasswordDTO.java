package cn.edu.seig.vibemusic.model.dto;

import cn.edu.seig.vibemusic.constant.MessageConstant;
import cn.edu.seig.vibemusic.constant.PasswordRules;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class UserResetPasswordDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户邮箱
     */
    @NotBlank(message = MessageConstant.EMAIL + MessageConstant.NOT_NULL)
    @Email(message = MessageConstant.EMAIL + MessageConstant.FORMAT_ERROR)
    private String email;

    /**
     * 验证码
     * 验证码格式：6位字符（大小写字母、数字）
     */
    @NotBlank(message = MessageConstant.VERIFICATION_CODE + MessageConstant.NOT_NULL)
    @Pattern(regexp = "^[0-9a-zA-Z]{6}$", message = MessageConstant.VERIFICATION_CODE + MessageConstant.FORMAT_ERROR)
    private String verificationCode;

    /**
     * 新密码
     * 密码格式：8-18 位，必须同时包含字母和数字，不能包含空格
     */
    @NotBlank(message = MessageConstant.PASSWORD + MessageConstant.NOT_NULL)
    @Pattern(regexp = PasswordRules.PATTERN, message = PasswordRules.MESSAGE)
    private String newPassword;

    /**
     * 确认密码
     * 只校验非空：与新密码是否一致由 service 层校验。
     */
    @NotBlank(message = MessageConstant.PASSWORD + MessageConstant.NOT_NULL)
    private String repeatPassword;

}
