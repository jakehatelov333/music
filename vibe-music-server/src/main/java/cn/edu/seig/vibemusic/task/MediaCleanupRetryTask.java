package cn.edu.seig.vibemusic.task;

import cn.edu.seig.vibemusic.service.MediaCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 媒体清理任务的定时兜底：只负责定期调用 worker 并记日志。
 *
 * <p>它<b>不是</b>可靠性来源，而是"提交后唤醒"丢失、进程重启、或唤醒时 MinIO 恰好不可用时的补偿。
 * 真正的可靠性来自 {@code tb_media_cleanup_task} 里的任务行 + 租约，因此本任务重复执行、并发执行、
 * 或与唤醒线程同时执行都是安全的（领取是原子条件更新）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaCleanupRetryTask {

    private final MediaCleanupService mediaCleanupService;

    @Scheduled(
            initialDelayString = "${media-cleanup.retry-initial-delay-ms:60000}",
            fixedDelayString = "${media-cleanup.retry-interval-ms:30000}"
    )
    public void retryPendingCleanups() {
        try {
            int processed = mediaCleanupService.runDueTasks();
            if (processed > 0) {
                log.info("媒体清理定时任务处理了 {} 条任务", processed);
            }
        } catch (RuntimeException e) {
            // 不能让一次异常终止后续调度；具体任务级失败已在 worker 内落 RETRY_WAIT
            log.warn("媒体清理定时任务执行失败：{}", e.getMessage());
        }
    }
}
