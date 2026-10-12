package cn.edu.seig.vibemusic;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
public class RedisConnectionTest {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private cn.edu.seig.vibemusic.security.RedisRateLimiter rateLimiter;

    @Test
    void concurrentRateLimitAllowsExactlyTheConfiguredQuota() {
        // 独立测试命名空间，60 秒自动过期，不触碰现有业务键。
        String bucket = "test-concurrent-" + java.util.UUID.randomUUID();
        java.util.List<java.util.concurrent.CompletableFuture<Long>> requests = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> rateLimiter.retryAfter(bucket, "test-user", 5, 60))).toList();
        java.util.List<Long> results = requests.stream().map(java.util.concurrent.CompletableFuture::join).toList();
        assertEquals(5L, results.stream().filter(value -> value == 0).count());
        org.junit.jupiter.api.Assertions.assertTrue(results.stream().allMatch(value -> value >= 0 && value <= 60));
    }

    @Test
    public void testRedisConnection() {
        // 测试设置和获取字符串值
        String key = "testKey";
        String value = "testValue";

        // 设置值
        stringRedisTemplate.opsForValue().set(key, value);

        // 获取值
        String retrievedValue = stringRedisTemplate.opsForValue().get(key);

        // 验证值是否正确
        assertEquals(value, retrievedValue, "Redis连接成功，值匹配");

        // 删除测试键
        stringRedisTemplate.delete(key);

        // 验证键是否被删除
        assertNull(stringRedisTemplate.opsForValue().get(key), "测试键已删除");
    }

}
