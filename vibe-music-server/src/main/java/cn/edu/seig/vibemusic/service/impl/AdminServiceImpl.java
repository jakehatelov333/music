package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.constant.MessageConstant;
import cn.edu.seig.vibemusic.enumeration.RoleEnum;
import cn.edu.seig.vibemusic.mapper.AdminMapper;
import cn.edu.seig.vibemusic.model.dto.AdminDTO;
import cn.edu.seig.vibemusic.model.entity.Admin;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.security.AuthSessionService;
import cn.edu.seig.vibemusic.security.PasswordService;
import cn.edu.seig.vibemusic.security.TokenExtractor;
import cn.edu.seig.vibemusic.service.IAdminService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author sunpingli
 * @since 2025-01-09
 */
@Service
public class AdminServiceImpl extends ServiceImpl<AdminMapper, Admin> implements IAdminService {

    @Autowired
    private AdminMapper adminMapper;
    @Autowired
    private PasswordService passwordService;
    @Autowired
    private AuthSessionService authSessionService;

    /**
     * 管理员注册。
     *
     * <p><b>不再暴露为 HTTP 路由</b>：{@code POST /admin/register} 已于 2026-10-15 下线
     * （该路径曾被 WebConfig 排除鉴权，匿名调用者可创建管理员并登录管理端）。
     * 本方法仅供后续设计的受控初始化入口复用，请勿重新挂到公开控制器上。
     *
     * @param adminDTO 管理员信息
     * @return 结果
     */
    @Override
    public Result register(AdminDTO adminDTO) {
        Admin admin = adminMapper.selectOne(new QueryWrapper<Admin>().eq("username", adminDTO.getUsername()));
        if (admin != null) {
            return Result.error(MessageConstant.USERNAME + MessageConstant.ALREADY_EXISTS);
        }

        String passwordHash = passwordService.encode(adminDTO.getPassword());
        Admin adminRegister = new Admin();
        adminRegister.setUsername(adminDTO.getUsername()).setPassword(passwordHash).setAuthVersion(0L);

        if (adminMapper.insert(adminRegister) == 0) {
            return Result.error(MessageConstant.REGISTER + MessageConstant.FAILED);
        }
        return Result.success(MessageConstant.REGISTER + MessageConstant.SUCCESS);
    }

    /**
     * 管理员登录
     *
     * @param adminDTO 管理员信息
     * @return 结果
     */
    @Override
    public Result login(AdminDTO adminDTO) {
        Admin admin = adminMapper.selectOne(new QueryWrapper<Admin>().eq("username", adminDTO.getUsername()));
        if (admin == null) {
            passwordService.dummyMatch(adminDTO.getPassword());
            return Result.error(MessageConstant.LOGIN + MessageConstant.FAILED);
        }
        if (!passwordService.matches(adminDTO.getPassword(), admin.getPassword())) {
            return Result.error(MessageConstant.LOGIN + MessageConstant.FAILED);
        }
        if (passwordService.needsUpgrade(admin.getPassword())) {
            adminMapper.update(new Admin().setPassword(passwordService.encode(adminDTO.getPassword())),
                    new QueryWrapper<Admin>().eq("id", admin.getAdminId()));
        }
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.ROLE, RoleEnum.ADMIN.getRole());
        claims.put(JwtClaimsConstant.ADMIN_ID, admin.getAdminId());
        claims.put(JwtClaimsConstant.USERNAME, admin.getUsername());
        Long authVersion = adminMapper.selectAuthVersionById(admin.getAdminId());
        if (authVersion == null) {
            throw new IllegalStateException("管理员缺少 auth_version，请先执行 phase14_auth_hardening.sql");
        }
        claims.put(JwtClaimsConstant.AUTH_VERSION, authVersion);
        String token = authSessionService.createSession(claims);

        return Result.success(MessageConstant.LOGIN + MessageConstant.SUCCESS, token);
    }

    /**
     * 登出
     *
     * @param token 认证token
     * @return 结果
     */
    @Override
    public Result logout(String token) {
        authSessionService.logout(TokenExtractor.extract(token));
        return Result.success(MessageConstant.LOGOUT + MessageConstant.SUCCESS);
    }
}
