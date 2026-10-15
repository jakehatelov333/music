package cn.edu.seig.vibemusic.service;

import cn.edu.seig.vibemusic.model.dto.AdminDTO;
import cn.edu.seig.vibemusic.model.entity.Admin;
import cn.edu.seig.vibemusic.result.Result;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author sunpingli
 * @since 2025-01-09
 */
public interface IAdminService extends IService<Admin> {

    /**
     * 管理员注册。
     *
     * <p><b>不再暴露为 HTTP 路由</b>：{@code POST /admin/register} 已于 2026-10-15 下线
     * （该路径曾被 WebConfig 排除鉴权，匿名调用者可创建管理员并登录管理端）。
     * 本方法仅供后续设计的受控初始化入口复用，请勿重新挂到公开控制器上。
     */
    Result register(AdminDTO adminDTO);

    // 管理员登录
    Result login(AdminDTO adminDTO);

    // 退出登录
    Result logout(String token);
}
