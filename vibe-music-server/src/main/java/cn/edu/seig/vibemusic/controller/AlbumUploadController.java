package cn.edu.seig.vibemusic.controller;

import cn.edu.seig.vibemusic.model.dto.AlbumUploadCommitDTO;
import cn.edu.seig.vibemusic.model.vo.AlbumUploadPrepareVO;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.IAlbumUploadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/album-upload")
public class AlbumUploadController {
    @Autowired private IAlbumUploadService albumUploadService;

    @PostMapping("/prepare")
    public Result<AlbumUploadPrepareVO> prepare(@RequestParam("audio") MultipartFile[] audio,
                                                 @RequestParam(value = "cover", required = false) MultipartFile cover) {
        return albumUploadService.prepare(audio, cover);
    }

    @PostMapping("/commit")
    public Result<Object> commit(@RequestBody AlbumUploadCommitDTO dto) { return albumUploadService.commit(dto); }

    /**
     * 放弃一次上传（管理端「放弃草稿」）。
     *
     * <p>走 {@code abort} 而不是 {@code clear}：用户主动放弃时，暂存文件删不掉必须
     * <b>明确报错并保留会话</b>，让管理员能重试；报「已清理」但文件还在，用户会以为删干净了。
     * {@code clear} 只用于提交成功后的垃圾清理（内部调用）。
     */
    @DeleteMapping("/{uploadId}")
    public Result<Object> clear(@PathVariable String uploadId) { return albumUploadService.abort(uploadId); }

    @PostMapping("/session")
    public Result<Object> createSession(@RequestBody List<IAlbumUploadService.AlbumUploadFileRequest> files) {
        return albumUploadService.createSession(files);
    }

    @PostMapping("/{uploadId}/sign")
    public Result<Object> sign(@PathVariable String uploadId, @RequestParam String fileName,
                               @RequestParam long size, @RequestParam(required = false) String contentType,
                               @RequestParam(defaultValue = "false") boolean cover) {
        return albumUploadService.signFile(uploadId, fileName, size, contentType, cover);
    }

    @PostMapping("/{uploadId}/complete")
    public Result<Object> complete(@PathVariable String uploadId, @RequestParam String fileName,
                                   @RequestParam long size, @RequestParam String objectName) {
        return albumUploadService.completeFile(uploadId, fileName, size, objectName);
    }

    @PostMapping("/{uploadId}/parse")
    public Result<AlbumUploadPrepareVO> parse(@PathVariable String uploadId) {
        return albumUploadService.parseStaging(uploadId);
    }

    @GetMapping("/{uploadId}")
    public Result<Object> session(@PathVariable String uploadId) { return albumUploadService.session(uploadId); }

    /**
     * 手动触发一次孤儿暂存文件回收（定时任务之外的补充入口）。
     * 只删除已无对应上传会话的 staging 目录，进行中的草稿不受影响。
     */
    @PostMapping("/cleanup-orphans")
    public Result<Object> cleanupOrphans() {
        return Result.success(Map.of("cleanedUploadIds", albumUploadService.cleanupOrphanStaging()));
    }
}
