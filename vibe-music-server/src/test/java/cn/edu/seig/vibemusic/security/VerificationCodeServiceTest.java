package cn.edu.seig.vibemusic.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VerificationCodeServiceTest {

    private StringRedisTemplate redis;
    private HashOperations<String, Object, Object> hashes;
    private VerificationCodeService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        service = new VerificationCodeService(redis);
    }

    @Test
    @DisplayName("生成 6 位字母数字验证码，写入哈希值而不是明文，并设置 5 分钟 TTL")
    void issuesHashedSingleUseCode() {
        String code = service.issue(VerificationPurpose.REGISTER, "User@Example.com");

        assertTrue(code.matches("[0-9A-Za-z]{6}"));
        verify(hashes).put(anyString(), eq("hash"), anyString());
        verify(hashes).put(anyString(), eq("attempts"), eq("0"));
        verify(redis).expire(anyString(), eq(5L), eq(TimeUnit.MINUTES));
        verify(hashes, org.mockito.Mockito.never()).put(anyString(), eq("hash"), eq(code));
    }

    @Test
    @DisplayName("正确验证码只能 claim 一次；第二次必须失败")
    void claimIsSingleUse() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L, -1L);

        VerificationCodeService.VerificationClaim claim =
                service.claim(VerificationPurpose.REGISTER, "user@example.com", "Abc123");
        assertTrue(claim.key().startsWith("verification:v2:REGISTER:"));

        assertThrows(ResponseStatusException.class, () ->
                service.claim(VerificationPurpose.REGISTER, "user@example.com", "Abc123"));
    }

    @Test
    @DisplayName("错误验证码达到上限时由 Lua 返回 exhausted，对调用方仍是统一错误")
    void exhaustedAttemptsAreRejected() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(-2L);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                service.claim(VerificationPurpose.RESET_PASSWORD, "user@example.com", "Wrong1"));
        assertEquals(400, error.getStatusCode().value());
    }

    @Test
    @DisplayName("格式非法：不访问 Redis")
    void malformedCodeIsRejectedBeforeRedis() {
        assertThrows(ResponseStatusException.class, () ->
                service.claim(VerificationPurpose.REGISTER, "user@example.com", "../../x"));
        verify(redis, org.mockito.Mockito.never())
                .execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    @DisplayName("finalize 删除已认领代码，release 允许事务失败后重试")
    void finalizeAndReleaseUseClaimId() {
        VerificationCodeService.VerificationClaim claim =
                new VerificationCodeService.VerificationClaim("verification:v2:REGISTER:hash", "claim-id");

        service.finalizeClaim(claim);
        service.releaseClaim(claim);

        verify(redis, org.mockito.Mockito.times(2))
                .execute(any(RedisScript.class), anyList(), any(Object[].class));
    }
}
