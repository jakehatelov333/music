package cn.edu.seig.vibemusic.controller;

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

    @PostMapping("/commit")
    public Result<SongUploadCommitVO> commit(@RequestBody @Valid SongUploadCommitDTO dto) {
        return songUploadService.commit(dto);
    }

    @DeleteMapping("/{uploadId}")
    public Result clear(@PathVariable("uploadId") String uploadId) {
        return songUploadService.clear(uploadId);
    }
}
