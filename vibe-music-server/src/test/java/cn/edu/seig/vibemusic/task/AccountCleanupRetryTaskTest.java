package cn.edu.seig.vibemusic.task;

import cn.edu.seig.vibemusic.mapper.AccountCleanupTaskMapper;
import cn.edu.seig.vibemusic.model.entity.AccountCleanupTask;
import cn.edu.seig.vibemusic.service.MinioService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 账号注销遗留清理任务的定时重试验收：成功标记 DONE，失败累计次数，达到上限落 FAILED。
 */
class AccountCleanupRetryTaskTest {

    private AccountCleanupTaskMapper mapper;
    private StringRedisTemplate redis;
    private MinioService minio;
    private AccountCleanupRetryTask task;

    @BeforeEach
    void setUp() {
        mapper = mock(AccountCleanupTaskMapper.class);
        redis = mock(StringRedisTemplate.class);
        minio = mock(MinioService.class);
        task = new AccountCleanupRetryTask(mapper, redis, minio);
    }

    private AccountCleanupTask task(long id, String type, String ref, int attempts) {
        AccountCleanupTask t = new AccountCleanupTask();
        t.setId(id);
        t.setUserId(1L);
        t.setResourceType(type);
        t.setResourceRef(ref);
        t.setAttempts(attempts);
        t.setCreatedAt(LocalDateTime.now());
        t.setUpdatedAt(LocalDateTime.now());
        return t;
    }

    @Test
    @DisplayName("Redis token 重试成功：作废 token 并标记 DONE")
    void retriesRedisTokenCleanup() {
        when(mapper.findRetryable(anyInt(), anyInt()))
                .thenReturn(List.of(task(1L, "REDIS_TOKEN", "t", 0)));

        task.retryPendingCleanups();

        verify(redis).delete("t");
        verify(mapper).markDone(eq(1L), any());
    }

    @Test
    @DisplayName("MinIO 头像重试失败但未达上限：累计次数并保持待重试")
    void keepsPendingWhenRetryFailsBelowLimit() {
        when(mapper.findRetryable(anyInt(), anyInt()))
                .thenReturn(List.of(task(2L, "MINIO_AVATAR", "http://x/a.png", 0)));
        doThrow(new RuntimeException("minio down")).when(minio).deleteFile("http://x/a.png");

        task.retryPendingCleanups();

        verify(mapper).markRetryFailed(eq(2L), any(), any());
        verify(mapper, never()).markDone(anyLong(), any());
        verify(mapper, never()).markExhausted(anyLong(), any(), any());
    }

    @Test
    @DisplayName("达到重试上限仍失败：落终态 FAILED，不再无限重试")
    void marksExhaustedAtLimit() {
        int lastChance = AccountCleanupRetryTask.MAX_ATTEMPTS - 1;
        when(mapper.findRetryable(anyInt(), anyInt()))
                .thenReturn(List.of(task(3L, "MINIO_AVATAR", "http://x/b.png", lastChance)));
        doThrow(new RuntimeException("minio down")).when(minio).deleteFile("http://x/b.png");

        task.retryPendingCleanups();

        verify(mapper).markExhausted(eq(3L), any(), any());
        verify(mapper, never()).markDone(anyLong(), any());
        verify(mapper, never()).markRetryFailed(anyLong(), any(), any());
    }
}
