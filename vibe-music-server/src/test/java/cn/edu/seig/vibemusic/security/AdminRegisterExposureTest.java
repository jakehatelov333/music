package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.config.*;
import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.controller.AdminController;
import cn.edu.seig.vibemusic.interceptor.*;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 管理员注册入口的下线与"不再免鉴权"回归。
 *
 * <p>背景：{@code POST /admin/register} 曾被 {@link WebConfig} 排除鉴权，且 {@code AdminServiceImpl.register}
 * 只查重名即插入，匿名调用者可创建管理员并登录管理端。本测试用两条互补断言锁住修复：
 * 一是端点本身已从真实控制器移除，二是即使有人把它加回来，白名单里也不会再放行。
 */
class AdminRegisterExposureTest {

    private static final String BODY = "{\"username\":\"attacker\",\"password\":\"whatever123\"}";

    /** 真实 AdminController 不再暴露 /admin/register。 */
    @Test
    @DisplayName("注册端点已下线：真实控制器返回 404")
    void registerEndpointIsRemovedFromController() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new AdminController()).build();

        mvc.perform(post("/admin/register").contentType("application/json").content(BODY))
                .andExpect(status().isNotFound());
    }

    /** 生产 WebConfig + 故意注册该路径的探针控制器：验证白名单确实已移除该条目。 */
    @Configuration
    @EnableWebMvc
    @Import({WebConfig.class, CorsConfig.class, LoginInterceptor.class})
    static class Config {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean AuthSessionService sessions() { return mock(AuthSessionService.class); }

        @Bean
        RolePermissionManager permissions() {
            var config = new RolePathPermissionsConfig();
            config.setPermissions(Map.of("ROLE_USER", List.of("/user/"), "ROLE_ADMIN", List.of("/admin/")));
            return new RolePermissionManager(config);
        }

        @Bean
        RateLimitInterceptor limits(ObjectMapper mapper) {
            return new RateLimitInterceptor(mock(RedisRateLimiter.class), mapper);
        }

        @Bean
        RegisterProbe probe() {
            return new RegisterProbe();
        }
    }

    @RestController
    static class RegisterProbe {
        @PostMapping("/admin/register")
        String register() {
            return "register-reached";
        }

        @PostMapping("/admin/login")
        String login() {
            return "login-reached";
        }
    }

    @Test
    @DisplayName("该路径不再免鉴权；登录白名单未被误伤")
    void registerPathIsNoLongerPublicWhileLoginStillIs() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(Config.class);
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();

            // 匿名调用注册路径：白名单已移除该条目，必须在拦截器处被拒，绝不能到达控制器
            mvc.perform(post("/admin/register").contentType("application/json").content(BODY))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("register-reached"))));

            // 登录入口仍在白名单内：匿名仍可到达控制器
            mvc.perform(post("/admin/login").contentType("application/json").content(BODY))
                    .andExpect(status().isOk())
                    .andExpect(content().string("login-reached"));

            // 管理员令牌仍能访问 /admin/ 前缀路径（角色前缀匹配未被破坏）
            String token = "valid-admin-token";
            Map<String, Object> claims = Map.of(
                    JwtClaimsConstant.ROLE, "ROLE_ADMIN", JwtClaimsConstant.ADMIN_ID, 1L);
            when(context.getBean(AuthSessionService.class).authenticate(token)).thenReturn(claims);

            mvc.perform(post("/admin/register").header("Authorization", token)
                            .contentType("application/json").content(BODY))
                    .andExpect(status().isOk())
                    .andExpect(content().string("register-reached"));
        } finally {
            ThreadLocalUtil.remove();
        }
    }
}
