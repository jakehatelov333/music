package cn.edu.seig.vibemusic.security;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@Component
@RequiredArgsConstructor
public class RedisRateLimiter {
    // 首次请求设置 TTL，计数及过期时间在同一脚本中执行，拒绝时返回剩余秒数。
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
            if n > tonumber(ARGV[1]) then return math.max(1, redis.call('TTL', KEYS[1])) end
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;

    public long retryAfter(String bucket, String identity, int limit, int seconds) {
        Long result = redis.execute(SCRIPT, List.of("rate:v1:" + bucket + ":" + digest(identity)),
                Integer.toString(limit), Integer.toString(seconds));
        if (result == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "限流服务暂不可用");
        return result;
    }

    private static String digest(String identity) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
