package cn.edu.seig.vibemusic.model.dto;

import cn.edu.seig.vibemusic.constant.MessageConstant;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class UserEmailChangeDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = MessageConstant.PASSWORD + MessageConstant.NOT_NULL)
    private String currentPassword;

    @NotBlank(message = MessageConstant.EMAIL + MessageConstant.NOT_NULL)
    @Email(message = MessageConstant.EMAIL + MessageConstant.FORMAT_ERROR)
    private String oldEmail;

    @NotBlank(message = MessageConstant.VERIFICATION_CODE + MessageConstant.NOT_NULL)
    @Pattern(regexp = "^[0-9a-zA-Z]{6}$", message = MessageConstant.VERIFICATION_CODE + MessageConstant.FORMAT_ERROR)
    private String oldEmailCode;

    @NotBlank(message = MessageConstant.EMAIL + MessageConstant.NOT_NULL)
    @Email(message = MessageConstant.EMAIL + MessageConstant.FORMAT_ERROR)
    private String newEmail;

    @NotBlank(message = MessageConstant.VERIFICATION_CODE + MessageConstant.NOT_NULL)
    @Pattern(regexp = "^[0-9a-zA-Z]{6}$", message = MessageConstant.VERIFICATION_CODE + MessageConstant.FORMAT_ERROR)
    private String newEmailCode;
}
