package cn.edu.seig.vibemusic.model.entity;

import cn.edu.seig.vibemusic.constant.MessageConstant;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * <p>
 *
 * </p>
 *
 * @author sunpingli
 * @since 2025-01-09
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_admin")
public class Admin implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 管理员 id
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long adminId;

    /**
     * 管理员用户名
     * 用户名格式：4-16位字符（字母、数字、下划线、连字符）
     */
    @NotBlank(message = MessageConstant.USERNAME + MessageConstant.NOT_NULL)
    @Pattern(regexp = "^[a-zA-Z0-9_-]{4,16}$", message = MessageConstant.USERNAME + MessageConstant.FORMAT_ERROR)
    @TableField("username")
    private String username;

    /**
     * 管理员密码
     * 实体不参与请求体校验；新密码为 BCrypt 哈希，登录时会透明升级历史 MD5。
     */
    @NotBlank(message = MessageConstant.PASSWORD + MessageConstant.NOT_NULL)
    @TableField("password")
    private String password;

    /**
     * 管理员安全版本。凭据或权限状态变化时递增，用于撤销已签发的 JWT。
     */
    @TableField("auth_version")
    private Long authVersion;

}
