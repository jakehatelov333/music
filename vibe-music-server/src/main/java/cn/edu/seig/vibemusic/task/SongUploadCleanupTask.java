package cn.edu.seig.vibemusic.task;

import cn.edu.seig.vibemusic.service.ISongUploadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时清理超过 12 小时没有活动的歌曲上传 staging 文件。
 *
 * <p>判据是「最后活动时间」而不是「创建时间」：上传会话每次保存都会刷新 zset 分数，
 * 只要管理员还在补充封面或歌词，文件就不会被清理。这个阈值与
 * {@code SongUploadServiceImpl.UPLOAD_TTL_HOURS} 是同一个值，改一处必须改两处。
 */
@Component
public class SongUploadCleanupTask {

    @Autowired
    private ISongUploadService songUploadService;

    @Scheduled(
            initialDelayString = "${song-upload.cleanup.initial-delay-ms:120000}",
            fixedDelayString = "${song-upload.cleanup.interval-ms:3600000}"
    )
    public void cleanupExpiredUploads() {
        songUploadService.cleanupExpired();
        // 再扫一遍「没有会话的 staging 目录」：prepare 中途失败等情况不会进 zset 索引，
        // 只靠上面的过期清理永远发现不了它们。
        try {
            songUploadService.cleanupOrphanStaging();
        } catch (Exception e) {
            // 兜底清扫失败不能影响主流程，下一轮再试
        }
    }
}
