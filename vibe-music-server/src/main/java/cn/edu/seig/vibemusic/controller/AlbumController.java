package cn.edu.seig.vibemusic.controller;

import cn.edu.seig.vibemusic.model.dto.AlbumQueryDTO;
import cn.edu.seig.vibemusic.model.vo.AlbumDetailVO;
import cn.edu.seig.vibemusic.model.vo.AlbumVO;
import cn.edu.seig.vibemusic.model.vo.SongVO;
import cn.edu.seig.vibemusic.result.PageResult;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.IAlbumService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户端专辑接口。
 */
@RestController
@RequestMapping("/album")
public class AlbumController {

    @Autowired
    private IAlbumService albumService;

    @GetMapping("/list")
    public Result<PageResult<AlbumVO>> getAlbumList(@Valid AlbumQueryDTO queryDTO) {
        queryDTO.setStatus("PUBLISHED");
        // 用户端不展示主艺人已停用（软删除）的专辑；管理端 /admin/getAllAlbums 不设置该开关，保持全量可见
        queryDTO.setExcludeDisabledArtist(true);
        return albumService.getAlbumList(queryDTO);
    }

    @GetMapping("/search")
    public Result<List<AlbumVO>> searchAlbums(@RequestParam(defaultValue = "") String keyword,
                                              @RequestParam(defaultValue = "10") int limit) {
        return albumService.searchAlbums(keyword, limit);
    }

    @GetMapping("/{id}/songs")
    public Result<List<SongVO>> getAlbumSongs(@PathVariable("id") Long albumId,
                                              HttpServletRequest request) {
        return albumService.getAlbumSongs(albumId, request);
    }

    @GetMapping("/{id}")
    public Result<AlbumDetailVO> getAlbumDetail(@PathVariable("id") Long albumId,
                                                HttpServletRequest request) {
        return albumService.getAlbumDetail(albumId, request);
    }
}
