package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.constant.MessageConstant;
import cn.edu.seig.vibemusic.enumeration.RoleEnum;
import cn.edu.seig.vibemusic.enumeration.UserStatusEnum;
import cn.edu.seig.vibemusic.mapper.AdminMapper;
import cn.edu.seig.vibemusic.mapper.UserMapper;
import cn.edu.seig.vibemusic.model.entity.Admin;
import cn.edu.seig.vibemusic.model.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Redis-backed session authority shared by the interceptor and all login/logout flows.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthSessionService {

    private static final String SESSION_PREFIX = "auth:session:";
    private static final String USER_INDEX_PREFIX = "auth:sessions:user:";
    private static final String ADMIN_INDEX_PREFIX = "auth:sessions:admin:";

    private static final DefaultRedisScript<Long> CREATE_SCRIPT = new DefaultRedisScript<>("""
            redis.call('SET', KEYS[1], '1', 'PX', ARGV[1])
            redis.call('SADD', KEYS[2], ARGV[2])
            redis.call('PEXPIRE', KEYS[2], ARGV[1])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final JwtService jwtService;
    private final UserMapper userMapper;
    private final AdminMapper adminMapper;

    public String createSession(Map<String, Object> claims) {
        String token = jwtService.generateToken(claims);
        String digest = digest(token);
        String role = String.valueOf(claims.get(JwtClaimsConstant.ROLE));
        String indexKey = indexKey(role, claims);
        if (indexKey == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, MessageConstant.SESSION_EXPIRED);
        }
        redis.execute(CREATE_SCRIPT, List.of(sessionKey(digest), indexKey),
                Long.toString(jwtService.ttl().toMillis()), digest);
        return token;
    }

    /**
     * Verifies JWT signature/expiry, Redis session, account existence/status, and auth_version.
     */
    public Map<String, Object> authenticate(String token) {
        Map<String, Object> claims = jwtService.parseToken(token);
        String digest = digest(token);
        if (!Boolean.TRUE.equals(redis.hasKey(sessionKey(digest)))) {
            throw unauthorized();
        }

        String role = String.valueOf(claims.get(JwtClaimsConstant.ROLE));
        if (RoleEnum.USER.getRole().equals(role)) {
            Long userId = positiveLong(claims.get(JwtClaimsConstant.USER_ID));
            User user = userMapper.selectById(userId);
            if (user == null || user.getUserStatus() != UserStatusEnum.ENABLE) {
                throw unauthorized();
            }
            Long version = userMapper.selectAuthVersionById(userId);
            if (version == null || !version.equals(claimAuthVersion(claims))) {
                throw unauthorized();
            }
        } else if (RoleEnum.ADMIN.getRole().equals(role)) {
            Long adminId = positiveLong(claims.get(JwtClaimsConstant.ADMIN_ID));
            Admin admin = adminMapper.selectById(adminId);
            if (admin == null) {
                throw unauthorized();
            }
            Long version = adminMapper.selectAuthVersionById(adminId);
            if (version == null || !version.equals(claimAuthVersion(claims))) {
                throw unauthorized();
            }
        } else {
            throw unauthorized();
        }
        return claims;
    }

    /**
     * Idempotent logout. A correctly signed token is enough to delete its digest; an
     * already-missing session still succeeds. Signature failures never delete anything.
     */
    public void logout(String token) {
        Map<String, Object> claims = jwtService.parseTokenAllowingExpired(token);
        String digest = digest(token);
        redis.delete(sessionKey(digest));
        String role = String.valueOf(claims.get(JwtClaimsConstant.ROLE));
        String indexKey = indexKey(role, claims);
        if (indexKey != null) {
            redis.opsForSet().remove(indexKey, digest);
        }
    }

    /**
     * Revokes all sessions for a principal. Callers must also increment auth_version in the
     * same business transaction so a Redis failure cannot keep old tokens alive.
     */
    public void revokeAllUserSessions(Long userId) {
        revokeAll(USER_INDEX_PREFIX + userId);
    }

    public boolean revokeUserSession(Long userId, String token) {
        if (userId == null || token == null) return false;
        String digest = digest(token);
        redis.delete(sessionKey(digest));
        redis.opsForSet().remove(USER_INDEX_PREFIX + userId, digest);
        return true;
    }

    public void revokeAllAdminSessions(Long adminId) {
        revokeAll(ADMIN_INDEX_PREFIX + adminId);
    }

    public String tokenDigest(String token) {
        return digest(token);
    }

    private void revokeAll(String indexKey) {
        try {
            var digests = redis.opsForSet().members(indexKey);
            if (digests != null) {
                for (String digest : digests) {
                    redis.delete(sessionKey(digest));
                }
            }
            redis.delete(indexKey);
        } catch (DataAccessException e) {
            log.error("清理 Redis 会话索引失败，将由 auth_version 闸门阻止旧令牌继续使用：{}", indexKey, e);
        }
    }

    private String indexKey(String role, Map<String, Object> claims) {
        if (RoleEnum.USER.getRole().equals(role)) {
            Long userId = positiveLongOrNull(claims.get(JwtClaimsConstant.USER_ID));
            return userId == null ? null : USER_INDEX_PREFIX + userId;
        }
        if (RoleEnum.ADMIN.getRole().equals(role)) {
            Long adminId = positiveLongOrNull(claims.get(JwtClaimsConstant.ADMIN_ID));
            return adminId == null ? null : ADMIN_INDEX_PREFIX + adminId;
        }
        return null;
    }

    private static String sessionKey(String digest) {
        return SESSION_PREFIX + digest;
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static Long positiveLong(Object value) {
        Long parsed = positiveLongOrNull(value);
        if (parsed == null) {
            throw unauthorized();
        }
        return parsed;
    }

    private static Long positiveLongOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value.toString());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long claimAuthVersion(Map<String, Object> claims) {
        Object value = claims.get(JwtClaimsConstant.AUTH_VERSION);
        if (value == null) {
            throw unauthorized();
        }
        try {
            long parsed = Long.parseLong(value.toString());
            return parsed >= 0 ? parsed : -1;
        } catch (NumberFormatException e) {
            throw unauthorized();
        }
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, MessageConstant.SESSION_EXPIRED);
    }
}
