package cn.edu.seig.vibemusic.task;

import cn.edu.seig.vibemusic.service.ISongUploadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时清理超过 24 小时仍未正式保存的歌曲上传 staging 文件。
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
    }
}
