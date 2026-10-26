package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.constant.MessageConstant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Purpose-scoped, hashed, single-use verification codes with an atomic attempt limit.
 */
@Service
@RequiredArgsConstructor
public class VerificationCodeService {

    private static final String CHARACTERS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int LENGTH = 6;
    private static final int MAX_ATTEMPTS = 5;
    private static final long TTL_MINUTES = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final DefaultRedisScript<Long> CLAIM_SCRIPT = new DefaultRedisScript<>("""
            local stored = redis.call('HGET', KEYS[1], 'hash')
            if not stored then return -1 end
            local attempts = tonumber(redis.call('HGET', KEYS[1], 'attempts') or '0')
            if attempts >= tonumber(ARGV[2]) then
              redis.call('DEL', KEYS[1])
              return -2
            end
            redis.call('HINCRBY', KEYS[1], 'attempts', 1)
            if stored == ARGV[1] then
              redis.call('HMSET', KEYS[1], 'state', 'CLAIMED', 'claim', ARGV[3])
              return 1
            end
            if attempts + 1 >= tonumber(ARGV[2]) then redis.call('DEL', KEYS[1]) end
            return 0
            """, Long.class);

    private static final DefaultRedisScript<Long> FINALIZE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'state') == 'CLAIMED'
               and redis.call('HGET', KEYS[1], 'claim') == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'state') == 'CLAIMED'
               and redis.call('HGET', KEYS[1], 'claim') == ARGV[1] then
              redis.call('HDEL', KEYS[1], 'state', 'claim')
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public String issue(VerificationPurpose purpose, String email) {
        String code = generateCode();
        String key = key(purpose, email);
        redis.delete(key);
        redis.opsForHash().put(key, "hash", hash(code));
        redis.opsForHash().put(key, "attempts", "0");
        redis.expire(key, TTL_MINUTES, TimeUnit.MINUTES);
        return code;
    }

    /**
     * Consumes the code. Missing/expired, exhausted, and wrong codes all fail without
     * revealing which condition applied.
     */
    public void consume(VerificationPurpose purpose, String email, String code) {
        VerificationClaim claim = claim(purpose, email, code);
        finalizeClaim(claim);
    }

    /**
     * Two-phase consume for flows where a business transaction may fail. The code is
     * atomically claimed first; callers must finalize after commit or release on failure.
     */
    public VerificationClaim claim(VerificationPurpose purpose, String email, String code) {
        if (code == null || !code.matches("^[0-9a-zA-Z]{6}$")) {
            throw invalidCode();
        }
        String claimId = UUID.randomUUID().toString();
        String key = key(purpose, email);
        Long result = redis.execute(CLAIM_SCRIPT, List.of(key),
                hash(code), Integer.toString(MAX_ATTEMPTS), claimId);
        if (result == null || result != 1L) {
            throw invalidCode();
        }
        return new VerificationClaim(key, claimId);
    }

    public void finalizeClaim(VerificationClaim claim) {
        if (claim == null) return;
        redis.execute(FINALIZE_SCRIPT, List.of(claim.key()), claim.id());
    }

    public void releaseClaim(VerificationClaim claim) {
        if (claim == null) return;
        redis.execute(RELEASE_SCRIPT, List.of(claim.key()), claim.id());
    }

    private String generateCode() {
        StringBuilder value = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            value.append(CHARACTERS.charAt(RANDOM.nextInt(CHARACTERS.length())));
        }
        return value.toString();
    }

    private static String key(VerificationPurpose purpose, String email) {
        if (purpose == null || email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, MessageConstant.EMAIL + MessageConstant.NOT_NULL);
        }
        return "verification:v2:" + purpose.name() + ":" + hash(email.trim().toLowerCase(Locale.ROOT));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static ResponseStatusException invalidCode() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                MessageConstant.VERIFICATION_CODE + MessageConstant.INVALID);
    }

    public record VerificationClaim(String key, String id) {
    }
}
