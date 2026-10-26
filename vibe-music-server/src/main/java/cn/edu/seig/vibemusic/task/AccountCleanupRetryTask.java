package cn.edu.seig.vibemusic.task;

import cn.edu.seig.vibemusic.mapper.AccountCleanupTaskMapper;
import cn.edu.seig.vibemusic.model.entity.AccountCleanupTask;
import cn.edu.seig.vibemusic.service.MinioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 重试账号注销后遗留的外部资源清理。
 *
 * <p>账号行在 MySQL 事务里删除并提交后，才会去清理 Redis token 与 MinIO 头像。外部系统不随
 * MySQL 事务回滚，一旦失败就落一条 {@code tb_account_cleanup_task} 记录，由本任务重试。
 *
 * <p>重试到 {@link #MAX_ATTEMPTS} 次仍失败则落终态 {@code FAILED}，等待人工处理；不会无限重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountCleanupRetryTask {

    /** 单条任务的最大尝试次数（含首次失败后落库的那一次）。 */
    static final int MAX_ATTEMPTS = 10;

    private static final int BATCH_LIMIT = 100;
    private static final String RESOURCE_REDIS_TOKEN = "REDIS_TOKEN";
    private static final String RESOURCE_MINIO_AVATAR = "MINIO_AVATAR";

    private final AccountCleanupTaskMapper cleanupTasks;
    private final MinioService minioService;

    @Scheduled(
            initialDelayString = "${account-cleanup.retry-initial-delay-ms:120000}",
            fixedDelayString = "${account-cleanup.retry-interval-ms:600000}"
    )
    public void retryPendingCleanups() {
        List<AccountCleanupTask> tasks = cleanupTasks.findRetryable(MAX_ATTEMPTS, BATCH_LIMIT);
        for (AccountCleanupTask task : tasks) {
            LocalDateTime now = LocalDateTime.now();
            try {
                cleanup(task);
                cleanupTasks.markDone(task.getId(), now);
            } catch (RuntimeException e) {
                String error = truncate(e.getMessage());
                int attempts = task.getAttempts() == null ? 0 : task.getAttempts();
                if (attempts + 1 >= MAX_ATTEMPTS) {
                    cleanupTasks.markExhausted(task.getId(), error, now);
                    log.warn("账号注销外部资源清理达到重试上限，任务={}，类型={}，原因={}",
                            task.getId(), task.getResourceType(), error);
                } else {
                    cleanupTasks.markRetryFailed(task.getId(), error, now);
                    log.info("账号注销外部资源清理重试失败，任务={}，类型={}，原因={}",
                            task.getId(), task.getResourceType(), error);
                }
            }
        }
    }

    private void cleanup(AccountCleanupTask task) {
        if (RESOURCE_REDIS_TOKEN.equals(task.getResourceType())) {
            // Session digests are no longer sufficient to reconstruct the Redis key without
            // the token. New deletions revoke through AuthSessionService before enqueueing;
            // legacy raw-token tasks are intentionally retired without logging the secret.
            log.warn("跳过历史明文 token 清理任务，任务={}", task.getId());
        } else if (RESOURCE_MINIO_AVATAR.equals(task.getResourceType())) {
            minioService.deleteFile(task.getResourceRef());
        } else {
            throw new IllegalStateException("未知的清理资源类型: " + task.getResourceType());
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
