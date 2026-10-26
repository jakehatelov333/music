package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.enumeration.UserStatusEnum;
import cn.edu.seig.vibemusic.mapper.AdminMapper;
import cn.edu.seig.vibemusic.mapper.UserMapper;
import cn.edu.seig.vibemusic.model.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthSessionServiceTest {

    private StringRedisTemplate redis;
    private JwtService jwt;
    private UserMapper userMapper;
    private AdminMapper adminMapper;
    private AuthSessionService sessions;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        jwt = mock(JwtService.class);
        userMapper = mock(UserMapper.class);
        adminMapper = mock(AdminMapper.class);
        sessions = new AuthSessionService(redis, jwt, userMapper, adminMapper);
    }

    @Test
    @DisplayName("有效会话：JWT、Redis 会话、账号状态与 auth_version 全部通过")
    void acceptsValidUserSession() {
        Map<String, Object> claims = userClaims(1L, 3L);
        when(jwt.parseToken("token")).thenReturn(claims);
        when(redis.hasKey(anyString())).thenReturn(true);
        when(userMapper.selectById(1L)).thenReturn(
                new User().setUserId(1L).setUserStatus(UserStatusEnum.ENABLE));
        when(userMapper.selectAuthVersionById(1L)).thenReturn(3L);

        assertEquals(claims, sessions.authenticate("token"));
    }

    @Test
    @DisplayName("Redis 会话缺失：即使 JWT 合法也必须拒绝")
    void rejectsMissingRedisSession() {
        when(jwt.parseToken("token")).thenReturn(userClaims(1L, 0L));
        when(redis.hasKey(anyString())).thenReturn(false);

        assertUnauthorized(() -> sessions.authenticate("token"));
        verify(userMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("auth_version 不匹配：旧令牌被拒绝")
    void rejectsStaleAuthVersion() {
        when(jwt.parseToken("token")).thenReturn(userClaims(1L, 2L));
        when(redis.hasKey(anyString())).thenReturn(true);
        when(userMapper.selectById(1L)).thenReturn(
                new User().setUserId(1L).setUserStatus(UserStatusEnum.ENABLE));
        when(userMapper.selectAuthVersionById(1L)).thenReturn(3L);

        assertUnauthorized(() -> sessions.authenticate("token"));
    }

    @Test
    @DisplayName("账号被禁用：已签发令牌立即失效")
    void rejectsDisabledAccount() {
        when(jwt.parseToken("token")).thenReturn(userClaims(1L, 0L));
        when(redis.hasKey(anyString())).thenReturn(true);
        when(userMapper.selectById(1L)).thenReturn(
                new User().setUserId(1L).setUserStatus(UserStatusEnum.DISABLE));

        assertUnauthorized(() -> sessions.authenticate("token"));
    }

    @Test
    @DisplayName("logout：删除会话摘要与用户索引，且允许重复调用")
    void logoutDeletesSessionAndIndex() {
        Map<String, Object> claims = userClaims(1L, 0L);
        when(jwt.parseTokenAllowingExpired("abc")).thenReturn(claims);
        var setOps = mock(org.springframework.data.redis.core.SetOperations.class);
        when(redis.opsForSet()).thenReturn(setOps);

        sessions.logout("abc");

        verify(redis).delete(anyString());
        verify(setOps).remove("auth:sessions:user:1", sessions.tokenDigest("abc"));
    }

    @Test
    @DisplayName("revokeAllUserSessions：清理索引中的全部会话")
    void revokeAllUserSessionsDeletesIndexedTokens() {
        var setOps = mock(org.springframework.data.redis.core.SetOperations.class);
        when(redis.opsForSet()).thenReturn(setOps);
        when(setOps.members("auth:sessions:user:1"))
                .thenReturn(java.util.Set.of("digest-a", "digest-b"));

        sessions.revokeAllUserSessions(1L);

        verify(redis).delete("auth:session:digest-a");
        verify(redis).delete("auth:session:digest-b");
        verify(redis).delete("auth:sessions:user:1");
    }

    @Test
    @DisplayName("createSession：按角色建立用户索引并返回 JWT")
    void createSessionStoresDigestInUserIndex() {
        when(jwt.generateToken(any())).thenReturn("issued-token");
        when(jwt.ttl()).thenReturn(java.time.Duration.ofHours(6));
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        assertEquals("issued-token", sessions.createSession(userClaims(1L, 0L)));
        verify(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    private static Map<String, Object> userClaims(long userId, long version) {
        return Map.of(
                JwtClaimsConstant.ROLE, "ROLE_USER",
                JwtClaimsConstant.USER_ID, userId,
                JwtClaimsConstant.AUTH_VERSION, version);
    }

    private static void assertUnauthorized(Runnable action) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(401, error.getStatusCode().value());
    }
}
