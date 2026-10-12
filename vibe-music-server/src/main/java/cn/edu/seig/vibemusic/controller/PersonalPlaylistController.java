package cn.edu.seig.vibemusic.controller;

import cn.edu.seig.vibemusic.model.dto.*;
import cn.edu.seig.vibemusic.model.vo.PlaylistDetailVO;
import cn.edu.seig.vibemusic.model.vo.PlaylistVO;
import cn.edu.seig.vibemusic.result.PageResult;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.PersonalPlaylistService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/playlist/mine")
@RequiredArgsConstructor
public class PersonalPlaylistController {
    private final PersonalPlaylistService playlists;

    @ModelAttribute
    public void preventPersonalResponseCaching(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @GetMapping
    public Result<PageResult<PlaylistVO>> mine(@Valid @ModelAttribute PlaylistDTO query) {
        return Result.success(playlists.mine(query));
    }

    @PostMapping
    public Result<PlaylistDetailVO> create(@RequestHeader("Idempotency-Key") String key,
                                          @Valid @RequestBody PersonalPlaylistCreateDTO input) {
        return Result.success(playlists.create(key, input));
    }

    @PutMapping("/{id}")
    public Result<PlaylistDetailVO> update(@PathVariable Long id, @Valid @RequestBody PersonalPlaylistDTO input) {
        return Result.success(playlists.update(id, input));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        playlists.delete(id);
        return Result.success();
    }

    @PostMapping("/{id}/songs")
    public Result<PlaylistDetailVO> addSongs(@PathVariable Long id, @Valid @RequestBody PlaylistSongsDTO input) {
        return Result.success(playlists.addSongs(id, input));
    }

    @DeleteMapping("/{id}/songs/{songId}")
    public Result<PlaylistDetailVO> removeSong(@PathVariable Long id, @PathVariable Long songId) {
        return Result.success(playlists.removeSong(id, songId));
    }

    /** 上传自定义封面；上传后不再被自动默认封面覆盖。 */
    @PatchMapping("/{id}/cover")
    public Result<PlaylistDetailVO> updateCover(@PathVariable Long id,
                                                @RequestParam("cover") MultipartFile cover) {
        return Result.success(playlists.updateCover(id, cover));
    }

    /** 恢复为「第一首歌曲封面」的自动默认封面。 */
    @DeleteMapping("/{id}/cover")
    public Result<PlaylistDetailVO> resetCover(@PathVariable Long id) {
        return Result.success(playlists.resetCover(id));
    }
}
