package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.config.*;
import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.interceptor.*;
import cn.edu.seig.vibemusic.util.JwtUtil;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 用<b>生产 WebConfig + LoginInterceptor</b> 验证管理端可观测性接口的权限边界。
 *
 * <p>要证明的是三件事，缺一不可：
 * <ol>
 *     <li>匿名访问 {@code /admin/ai/**} 被 401 拦下（不会因为新增端点漏配而裸奔）；</li>
 *     <li>普通用户 {@code ROLE_USER} 访问被 403 拒绝（不能改个 traceId 就看别人的记录）；</li>
 *     <li>管理员 {@code ROLE_ADMIN} 可以正常访问。</li>
 * </ol>
 *
 * <p>这里刻意用真实的路径前缀与真实的权限映射（{@code /admin/} → ROLE_ADMIN），
 * 而不是在测试里另写一套规则 —— 要验证的正是"生产配置本身是否覆盖了新端点"。
 */
class AdminAiTraceAccessTest {

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

        @Bean RolePermissionManager permissions() {
            // 与 application.yml 的 role-path-permissions 保持一致。
            var config = new RolePathPermissionsConfig();
            config.setPermissions(Map.of(
                    "ROLE_ADMIN", List.of("/admin/"),
                    "ROLE_USER", List.of("/user/", "/playlist/", "/artist/", "/song/", "/album/",
                            "/favorite/", "/play-history/", "/comment/", "/banner/", "/feedback/", "/ai/")));
            return new RolePermissionManager(config);
        }

        @Bean RateLimitInterceptor limits(ObjectMapper mapper) {
            return new RateLimitInterceptor(mock(RedisRateLimiter.class), mapper);
        }

        @Bean AdminEndpoint adminEndpoint() { return new AdminEndpoint(); }
    }

    /** 只用来验证"路径是否可达"，不承载真实业务。 */
    @RestController
    static class AdminEndpoint {
        @GetMapping({"/admin/ai/overview", "/admin/ai/traces/{traceId}", "/admin/ai/users/options"})
        String reachable() { return "ok"; }
    }

    @Test
    @DisplayName("匿名访问管理端可观测性接口返回 401")
    void anonymousIsRejected() throws Exception {
        try (var context = new AnnotationWebContext()) {
            context.mvc().perform(get("/admin/ai/overview")).andExpect(status().isUnauthorized());
            context.mvc().perform(get("/admin/ai/traces/abc")).andExpect(status().isUnauthorized());
            context.mvc().perform(get("/admin/ai/users/options")).andExpect(status().isUnauthorized());
        } finally {
            ThreadLocalUtil.remove();
        }
    }

    @Test
    @DisplayName("普通用户访问管理端可观测性接口返回 403，不能借 traceId 越权")
    void regularUserIsForbidden() throws Exception {
        try (var context = new AnnotationWebContext()) {
            String token = JwtUtil.generateToken(Map.of(
                    JwtClaimsConstant.USER_ID, 7L, JwtClaimsConstant.ROLE, "ROLE_USER"));
            when(context.values().get(token)).thenReturn(token);
            context.mvc().perform(get("/admin/ai/overview").header("Authorization", token))
                    .andExpect(status().isForbidden());
            context.mvc().perform(get("/admin/ai/traces/someone-elses-trace").header("Authorization", token))
                    .andExpect(status().isForbidden());
        } finally {
            ThreadLocalUtil.remove();
        }
    }

    @Test
    @DisplayName("管理员可以访问管理端可观测性接口")
    void adminIsAllowed() throws Exception {
        try (var context = new AnnotationWebContext()) {
            String token = JwtUtil.generateToken(Map.of(
                    JwtClaimsConstant.ADMIN_ID, 1L, JwtClaimsConstant.ROLE, "ROLE_ADMIN"));
            when(context.values().get(token)).thenReturn(token);
            context.mvc().perform(get("/admin/ai/overview").header("Authorization", token))
                    .andExpect(status().isOk());
            context.mvc().perform(get("/admin/ai/traces/abc").header("Authorization", token))
                    .andExpect(status().isOk());
        } finally {
            ThreadLocalUtil.remove();
        }
    }

    /** 每个用例独立的 Spring 上下文与 Redis 替身，避免 token 桩互相污染。 */
    private static final class AnnotationWebContext implements AutoCloseable {
        private final AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();

        AnnotationWebContext() {
            context.setServletContext(new MockServletContext());
            context.register(Config.class);
            context.refresh();
        }

        org.springframework.test.web.servlet.MockMvc mvc() {
            return MockMvcBuilders.webAppContextSetup(context).build();
        }

        ValueOperations<String, String> values() {
            return context.getBean(StringRedisTemplate.class).opsForValue();
        }

        @Override
        public void close() {
            context.close();
        }
    }
}
