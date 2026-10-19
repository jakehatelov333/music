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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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

    @DeleteMapping("/{uploadId}")
    public Result clear(@PathVariable("uploadId") String uploadId) {
        return songUploadService.clear(uploadId);
    }
}
