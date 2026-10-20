package cn.edu.seig.vibemusic.controller;

import cn.edu.seig.vibemusic.model.dto.CoverFromAlbumDTO;
import cn.edu.seig.vibemusic.model.dto.SongUploadCommitDTO;
import cn.edu.seig.vibemusic.model.vo.SongUploadCommitVO;
import cn.edu.seig.vibemusic.model.vo.SongUploadPrepareVO;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.ISongUploadService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 管理员歌曲上传工作流接口。
 */
@RestController
@RequestMapping("/admin/song-upload")
public class SongUploadController {

    @Autowired
    private ISongUploadService songUploadService;

    @PostMapping("/prepare")
    public Result<SongUploadPrepareVO> prepare(@RequestParam("audio") MultipartFile audio) {
        return songUploadService.prepare(audio);
    }

    @PostMapping("/{uploadId}/assets")
    public Result<SongUploadPrepareVO> updateAssets(
            @PathVariable("uploadId") String uploadId,
            @RequestParam(value = "cover", required = false) MultipartFile cover,
            @RequestParam(value = "lyrics", required = false) MultipartFile lyrics) {
        return songUploadService.updateAssets(uploadId, cover, lyrics);
    }

    /**
     * 把选中的 QQ 音乐专辑封面下载到该上传会话的 staging 目录。
     *
     * <p>只收 {@code albumMid} 与 {@code size}，<b>不收 URL</b>：允许传 URL 等于给管理端
     * 开一个任意地址抓取入口（SSRF）。服务端按固定模板拼地址，mid 走白名单校验。
     *
     * <p>下载后与手动上传的封面走完全相同的后续路径，因此不需要为"搜来的封面"
     * 单独实现入库逻辑。
     */
    @PostMapping("/{uploadId}/cover-from-album")
    public Result<SongUploadPrepareVO> coverFromAlbum(
            @PathVariable("uploadId") String uploadId,
            @RequestBody CoverFromAlbumDTO dto) {
        return songUploadService.updateCoverFromAlbum(uploadId,
                dto == null ? null : dto.getAlbumMid(),
                dto == null ? null : dto.getSize());
    }

    @PostMapping("/commit")
    public Result<SongUploadCommitVO> commit(@RequestBody @Valid SongUploadCommitDTO dto) {
        return songUploadService.commit(dto);
    }

    /**
     * 读取仍未过期的上传会话（管理端草稿箱的「继续 / 刷新后恢复」入口）。
     *
     * <p>非幂等、非只读的清理逻辑不放在这里：过期判定只由 Redis TTL 与定时任务负责，
     * 本接口只读。会话已过期时返回 {@code data = null}。
     */
    @GetMapping("/{uploadId}")
    public Result<SongUploadPrepareVO> session(@PathVariable("uploadId") String uploadId) {
        return songUploadService.session(uploadId);
    }

    @DeleteMapping("/{uploadId}")
    public Result clear(@PathVariable("uploadId") String uploadId) {
        return songUploadService.clear(uploadId);
    }

    /**
     * 手动触发一次孤儿暂存文件回收（定时任务之外的补充入口）。
     *
     * <p>只删除「已无对应 Redis 会话」的 staging 目录：进行中的草稿会话仍在，绝不会被误删。
     */
    @PostMapping("/cleanup-orphans")
    public Result<Map<String, Integer>> cleanupOrphans() {
        return Result.success(Map.of("cleanedUploadIds", songUploadService.cleanupOrphanStaging()));
    }
}
