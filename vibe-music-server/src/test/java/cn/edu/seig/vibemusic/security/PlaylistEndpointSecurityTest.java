package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.config.*;
import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.interceptor.*;
import cn.edu.seig.vibemusic.util.JwtUtil;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Use the production WebConfig registrations to catch accidental public exclusions. */
class PlaylistEndpointSecurityTest {
    @Configuration @EnableWebMvc
    @Import({WebConfig.class, CorsConfig.class, LoginInterceptor.class})
    static class Config {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean StringRedisTemplate redis() {
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
            return redis;
        }
        @Bean RolePermissionManager permissions() {
            var config = new RolePathPermissionsConfig();
            config.setPermissions(Map.of("ROLE_USER", List.of("/playlist/"), "ROLE_ADMIN", List.of("/admin/")));
            return new RolePermissionManager(config);
        }
        @Bean RateLimitInterceptor limits(ObjectMapper mapper) {
            return new RateLimitInterceptor(mock(RedisRateLimiter.class), mapper);
        }
        @Bean Endpoint endpoint() { return new Endpoint(); }
    }
    @RestController
    static class Endpoint {
        @GetMapping({"/playlist/mine", "/playlist/getPlaylistDetail/{id}", "/playlist/getRecommendedPlaylists"})
        String identity() { return CurrentUser.id() == null ? "guest" : CurrentUser.id().toString(); }
        @PostMapping("/playlist/mine") String create() { return "created"; }
    }

    @Test void optionalAuthenticationIsVerifiedAndPersonalRoutesRequireLogin() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(Config.class); context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            String token = JwtUtil.generateToken(Map.of(JwtClaimsConstant.USER_ID, 7L, JwtClaimsConstant.ROLE, "ROLE_USER"));
            var values = context.getBean(StringRedisTemplate.class).opsForValue();
            when(values.get(token)).thenReturn(token);
            mvc.perform(get("/playlist/mine")).andExpect(status().isUnauthorized());
            mvc.perform(get("/playlist/mine").header("Authorization", token))
                    .andExpect(status().isOk()).andExpect(content().string("7"));
            for (String path : List.of("/playlist/getPlaylistDetail/42", "/playlist/getRecommendedPlaylists")) {
                mvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().string("guest"));
                mvc.perform(get(path).header("Authorization", token))
                        .andExpect(status().isOk()).andExpect(content().string("7"));
                when(values.get(token)).thenReturn(null);
                mvc.perform(get(path).header("Authorization", token)).andExpect(status().isUnauthorized());
                when(values.get(token)).thenReturn(token);
            }
            mvc.perform(options("/playlist/mine").header("Origin", "http://localhost:8090")
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "Authorization,Idempotency-Key,Content-Type"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8090"));
        } finally { ThreadLocalUtil.remove(); }
    }
}
