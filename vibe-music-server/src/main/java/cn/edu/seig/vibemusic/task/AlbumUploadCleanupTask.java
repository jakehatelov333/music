package cn.edu.seig.vibemusic.task;

import cn.edu.seig.vibemusic.service.IAlbumUploadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时回收孤儿专辑上传暂存文件。
 *
 * <p>上传会话的有效期由 {@code album-upload.session-ttl-hours} 决定（默认 12 小时，签约与
 * 完成单个文件都会续期）。会话一旦过期就无法再续传，其 staging 目录属于垃圾数据；因此以
 * 「Redis 中是否还存在该上传会话」作为判据，不存在则删除对应的 staging 目录。</p>
 *
 * <p>额外要求目录最近 10 分钟内没有写入：{@code prepare} 是「先传完文件、再写会话」，
 * 那段窗口里同样是「有文件、无会话」，只按会话判断会把正在上传的专辑删掉。</p>
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
