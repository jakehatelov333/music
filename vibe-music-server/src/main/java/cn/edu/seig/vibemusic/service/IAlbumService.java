package cn.edu.seig.vibemusic.service;

import cn.edu.seig.vibemusic.model.dto.AlbumQueryDTO;
import cn.edu.seig.vibemusic.model.dto.AlbumAddDTO;
import cn.edu.seig.vibemusic.model.dto.AlbumUpdateDTO;
import cn.edu.seig.vibemusic.model.entity.Album;
import cn.edu.seig.vibemusic.model.vo.AlbumDetailVO;
import cn.edu.seig.vibemusic.model.vo.AlbumVO;
import cn.edu.seig.vibemusic.model.vo.SongVO;
import cn.edu.seig.vibemusic.result.PageResult;
import cn.edu.seig.vibemusic.result.Result;
import com.baomidou.mybatisplus.extension.service.IService;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * 专辑业务接口。
 */
public interface IAlbumService extends IService<Album> {

    Result<PageResult<AlbumVO>> getAlbumList(AlbumQueryDTO queryDTO);

    Result<AlbumDetailVO> getAlbumDetail(Long albumId, HttpServletRequest request);

    Result<List<SongVO>> getAlbumSongs(Long albumId, HttpServletRequest request);

    Result<List<AlbumVO>> searchAlbums(String keyword, int limit);

    /**
     * 精确判断指定艺人的专辑是否存在。
     */
    Result<Boolean> albumExists(Long artistId, String albumName);

    Result<Long> getAllAlbumsCount();

    Result addAlbum(AlbumAddDTO albumAddDTO);

    Result updateAlbum(AlbumUpdateDTO albumUpdateDTO);

    Result updateAlbumCover(Long albumId, String coverUrl);

    Result deleteAlbum(Long albumId);

    /**
     * 删除专辑。
     *
     * @param albumId     专辑id
     * @param deleteSongs 是否连同专辑内的所有歌曲一起删除（true 时歌曲及其音频文件、评论、收藏、歌单关联均被清理）
     * @return 结果
     */
    Result deleteAlbum(Long albumId, boolean deleteSongs);

    Result deleteAlbums(List<Long> albumIds);
}
