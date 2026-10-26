package cn.edu.seig.vibemusic.controller;


import cn.edu.seig.vibemusic.constant.MessageConstant;
import cn.edu.seig.vibemusic.model.dto.*;
import cn.edu.seig.vibemusic.model.vo.AccountDeletionResultVO;
import cn.edu.seig.vibemusic.model.vo.UserVO;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.security.VerificationPurpose;
import cn.edu.seig.vibemusic.service.IUserService;
import cn.edu.seig.vibemusic.service.MinioService;
import cn.edu.seig.vibemusic.util.BindingResultUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import org.hibernate.validator.constraints.URL;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author sunpingli
 * @since 2025-01-09
 */
@RestController
@RequestMapping("/user")
public class UserController {

    @Autowired
    private IUserService userService;
    /**
     * 发送验证码
     *
     * @param email 邮箱
     * @return 结果
     */
    @GetMapping("/sendVerificationCode")
    public Result sendVerificationCode(@RequestParam @Email String email,
                                       @RequestParam VerificationPurpose purpose) {
        return userService.sendVerificationCode(email, purpose);
    }

    /**
     * 注册
     *
     * @param userRegisterDTO 用户注册信息
     * @param bindingResult   绑定结果
     * @return 结果
     */
    @PostMapping("/register")
    public Result register(@RequestBody @Valid UserRegisterDTO userRegisterDTO, BindingResult bindingResult) {
        // 校验失败时，返回错误信息
        String errorMessage = BindingResultUtil.handleBindingResultErrors(bindingResult);
        if (errorMessage != null) {
            return Result.error(errorMessage);
        }

        return userService.register(userRegisterDTO);
    }

    /**
     * 登录
     *
     * @param userLoginDTO  用户登录信息
     * @param bindingResult 绑定结果
     * @return 结果
     */
    @PostMapping("/login")
    public Result login(@RequestBody @Valid UserLoginDTO userLoginDTO, BindingResult bindingResult) {
        // 校验失败时，返回错误信息
        String errorMessage = BindingResultUtil.handleBindingResultErrors(bindingResult);
        if (errorMessage != null) {
            return Result.error(errorMessage);
        }

        return userService.login(userLoginDTO);
    }

    /**
     * 获取用户信息
     *
     * @return 结果
     */
    @GetMapping("/getUserInfo")
    public Result<UserVO> getUserInfo() {
        return userService.userInfo();
    }

    /**
     * 更新用户信息
     *
     * @param userDTO 用户信息
     * @return 结果
     */
    @PutMapping("/updateUserInfo")
    public Result updateUserInfo(@RequestBody @Valid UserDTO userDTO, BindingResult bindingResult) {
        // 校验失败时，返回错误信息
        String errorMessage = BindingResultUtil.handleBindingResultErrors(bindingResult);
        if (errorMessage != null) {
            return Result.error(errorMessage);
        }

        return userService.updateUserInfo(userDTO);
    }

    /**
     * 更新用户头像
     *
     * @param avatar 头像
     * @return 结果
     */
    @PatchMapping("/updateUserAvatar")
    public Result updateUserAvatar(@RequestParam("avatar") MultipartFile avatar) {
        return userService.updateUserAvatar(avatar);
    }

    /**
     * 更新用户密码
     *
     * @param userPasswordDTO 用户密码信息
     * @return 结果
     */
    @PatchMapping("/updateUserPassword")
    public Result updateUserPassword(@RequestBody @Valid UserPasswordDTO userPasswordDTO,
                                     BindingResult bindingResult) {
        // 校验失败时，返回错误信息
        String errorMessage = BindingResultUtil.handleBindingResultErrors(bindingResult);
        if (errorMessage != null) {
            return Result.error(errorMessage);
        }

        return userService.updateUserPassword(userPasswordDTO);
    }

    /**
     * 重置用户密码
     *
     * @param userResetPasswordDTO 用户密码信息
     * @return 结果
     */
    @PatchMapping("/resetUserPassword")
    public Result resetUserPassword(@RequestBody @Valid UserResetPasswordDTO userResetPasswordDTO, BindingResult bindingResult) {
        // 校验失败时，返回错误信息
        String errorMessage = BindingResultUtil.handleBindingResultErrors(bindingResult);
        if (errorMessage != null) {
            return Result.error(errorMessage);
        }

        return userService.resetUserPassword(userResetPasswordDTO);
    }

    /**
     * 换绑邮箱：必须同时通过当前密码、原邮箱验证码和新邮箱验证码。
     */
    @PatchMapping("/changeEmail")
    public Result changeEmail(@RequestBody @Valid UserEmailChangeDTO userEmailChangeDTO,
                              BindingResult bindingResult) {
        String errorMessage = BindingResultUtil.handleBindingResultErrors(bindingResult);
        if (errorMessage != null) {
            return Result.error(errorMessage);
        }
        return userService.changeEmail(userEmailChangeDTO);
    }

    /**
     * 登出
     *
     * @param token 认证token
     * @return 结果
     */
    @PostMapping("/logout")
    public Result logout(@RequestHeader("Authorization") String token) {
        return userService.logout(token);
    }

    /**
     * 注销账号
     *
     * @return 结果
     */
    @DeleteMapping("/deleteAccount")
    public Result<AccountDeletionResultVO> deleteAccount(@RequestHeader("Authorization") String token) {
        return userService.deleteAccount(token);
    }

}
