package cn.edu.seig.vibemusic.playhistory;

import cn.edu.seig.vibemusic.config.CorsConfig;
import cn.edu.seig.vibemusic.config.RolePathPermissionsConfig;
import cn.edu.seig.vibemusic.config.RolePermissionManager;
import cn.edu.seig.vibemusic.config.WebConfig;
import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.interceptor.LoginInterceptor;
import cn.edu.seig.vibemusic.interceptor.RateLimitInterceptor;
import cn.edu.seig.vibemusic.security.CurrentUser;
import cn.edu.seig.vibemusic.security.RedisRateLimiter;
import cn.edu.seig.vibemusic.util.JwtUtil;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 播放历史接口的访问控制。
 *
 * <p>用<b>生产环境的 {@link WebConfig} 与 {@link LoginInterceptor}</b> 跑 MockMvc，
 * 因此这里验证的是「真实拦截链路」，而不是在测试里另写一份路径判断 ——
 * 后者只会在代码改动后依然通过。
 *
 * <p>关键点：{@code /play-history/**} 既不能出现在 {@code excludePathPatterns} 里，
 * 也不能出现在 {@code PathConstant} 的匿名白名单里。两者任意一处漏了，
 * 游客就能读到别人的历史（因为 Service 层的 401 根本不会被触发）。
 */
class PlayHistoryEndpointSecurityTest {

    @Configuration
    @EnableWebMvc
    @Import({WebConfig.class, CorsConfig.class, LoginInterceptor.class})
    static class Config {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }

        @Bean StringRedisTemplate redis() {
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
            return redis;
        }

        /** 与 application.yml 的 role-path-permissions 保持一致（只列与本测试相关的部分）。 */
        @Bean RolePermissionManager permissions() {
            var config = new RolePathPermissionsConfig();
            config.setPermissions(Map.of(
                    "ROLE_USER", List.of("/play-history/", "/favorite/"),
                    "ROLE_ADMIN", List.of("/admin/")));
            return new RolePermissionManager(config);
        }

        @Bean RateLimitInterceptor limits(ObjectMapper mapper) {
            return new RateLimitInterceptor(mock(RedisRateLimiter.class), mapper);
        }

        @Bean Endpoint endpoint() { return new Endpoint(); }
    }

    /** 复刻真实控制器的路径与方法，只把返回值换成「当前用户 id」以便断言身份。 */
    @RestController
    static class Endpoint {
        @PostMapping("/play-history/record")
        String record() { return identity(); }

        @PostMapping("/play-history/page")
        String page() { return identity(); }

        @GetMapping("/play-history/recent")
        String recent() { return identity(); }

        @DeleteMapping("/play-history/{historyId}")
        String deleteOne(@PathVariable String historyId) { return identity() + ":" + historyId; }

        @DeleteMapping("/play-history/clear")
        String clear() { return identity(); }

        private String identity() {
            Long id = CurrentUser.id();
            return id == null ? "guest" : id.toString();
        }
    }

    @AfterEach
    void cleanup() {
        ThreadLocalUtil.remove();
    }

    @Test
    void everyPlayHistoryEndpointRequiresLogin() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(Config.class);
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();

            // 游客：全部 401，一个都不能放行
            mvc.perform(post("/play-history/record")).andExpect(status().isUnauthorized());
            mvc.perform(post("/play-history/page")).andExpect(status().isUnauthorized());
            mvc.perform(get("/play-history/recent")).andExpect(status().isUnauthorized());
            mvc.perform(delete("/play-history/1")).andExpect(status().isUnauthorized());
            mvc.perform(delete("/play-history/clear")).andExpect(status().isUnauthorized());

            // 携带有效 token：能进到控制器，且身份就是 token 里的用户
            String token = JwtUtil.generateToken(Map.of(
                    JwtClaimsConstant.USER_ID, 7L, JwtClaimsConstant.ROLE, "ROLE_USER"));
            var values = context.getBean(StringRedisTemplate.class).opsForValue();
            when(values.get(token)).thenReturn(token);

            mvc.perform(post("/play-history/record").header("Authorization", token))
                    .andExpect(status().isOk()).andExpect(content().string("7"));
            mvc.perform(post("/play-history/page").header("Authorization", token))
                    .andExpect(status().isOk()).andExpect(content().string("7"));
            mvc.perform(get("/play-history/recent").header("Authorization", token))
                    .andExpect(status().isOk()).andExpect(content().string("7"));
            mvc.perform(delete("/play-history/clear").header("Authorization", token))
                    .andExpect(status().isOk()).andExpect(content().string("7"));
            // 路径变量只影响资源 id，身份仍然来自 token
            mvc.perform(delete("/play-history/42").header("Authorization", token))
                    .andExpect(status().isOk()).andExpect(content().string("7:42"));

            // token 在 Redis 中失效 -> 401（会话过期不能被当成游客放行）
            when(values.get(token)).thenReturn(null);
            mvc.perform(get("/play-history/recent").header("Authorization", token))
                    .andExpect(status().isUnauthorized());
        } finally {
            ThreadLocalUtil.remove();
        }
    }

    /**
     * 管理员角色没有 {@code /play-history/} 前缀权限，即使 token 有效也应被 403 拦下 ——
     * 播放历史只属于用户自己。
     */
    @Test
    void adminRoleCannotReadUserPlayHistory() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(Config.class);
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();

            String token = JwtUtil.generateToken(Map.of(
                    JwtClaimsConstant.USER_ID, 1L, JwtClaimsConstant.ROLE, "ROLE_ADMIN"));
            var values = context.getBean(StringRedisTemplate.class).opsForValue();
            when(values.get(token)).thenReturn(token);

            mvc.perform(get("/play-history/recent").header("Authorization", token))
                    .andExpect(status().isForbidden());
        } finally {
            ThreadLocalUtil.remove();
        }
    }
}
