package cn.edu.seig.vibemusic.task;

import cn.edu.seig.vibemusic.service.IAlbumUploadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时回收孤儿专辑上传暂存文件。
 *
 * <p>上传会话在 Redis 中保留 48 小时，会话一旦过期就无法再续传，其 staging 目录属于垃圾数据；
 * 因此以「Redis 中是否还存在该上传会话」作为唯一判据，不存在则删除对应的 staging 目录。</p>
 */
@Component
public class AlbumUploadCleanupTask {

    @Autowired
    private IAlbumUploadService albumUploadService;

    @Scheduled(
            initialDelayString = "${album-upload.cleanup.initial-delay-ms:120000}",
            fixedDelayString = "${album-upload.cleanup.interval-ms:3600000}"
    )
    public void cleanupExpiredUploads() {
        albumUploadService.cleanupOrphanStaging();
    }
}
