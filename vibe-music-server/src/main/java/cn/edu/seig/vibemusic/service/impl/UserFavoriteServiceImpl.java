package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.security.CurrentUser;
import cn.edu.seig.vibemusic.security.PlaylistAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import cn.edu.seig.vibemusic.constant.MessageConstant;
import cn.edu.seig.vibemusic.enumeration.LikeStatusEnum;
import cn.edu.seig.vibemusic.mapper.PlaylistMapper;
import cn.edu.seig.vibemusic.mapper.SongMapper;
import cn.edu.seig.vibemusic.mapper.UserFavoriteMapper;
import cn.edu.seig.vibemusic.model.dto.PlaylistDTO;
import cn.edu.seig.vibemusic.model.dto.SongDTO;
import cn.edu.seig.vibemusic.model.entity.Playlist;
import cn.edu.seig.vibemusic.model.entity.UserFavorite;
import cn.edu.seig.vibemusic.model.vo.PlaylistVO;
import cn.edu.seig.vibemusic.model.vo.SongVO;
import cn.edu.seig.vibemusic.result.PageResult;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.IUserFavoriteService;
import cn.edu.seig.vibemusic.service.SongArtistService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author sunpingli
 * @since 2025-01-09
 */
@Service
@CacheConfig(cacheNames = "userFavoriteCache")
public class UserFavoriteServiceImpl extends ServiceImpl<UserFavoriteMapper, UserFavorite> implements IUserFavoriteService {

    @Autowired
    private UserFavoriteMapper userFavoriteMapper;
    @Autowired
    private SongMapper songMapper;
    @Autowired
    private PlaylistMapper playlistMapper;
    @Autowired
    private PlaylistAccessService playlistAccessService;
    @Autowired
    private SongArtistService songArtistService;

    /**
     * 获取用户收藏的歌曲列表
     *
     *
     * 作用：将方法的返回值缓存起来，下次请求时直接返回缓存结果，避免重复查询数据库。
     * 缓存键（key）：
     * 动态生成，由 songDTO 的多个字段拼接而成，格式为：
     * "v2:业务类型:userId:查询参数JSON"
     * 不同用户、不同业务使用独立缓存键；旧缓存键不再读取。
     * 触发条件：
     * 仅当缓存中不存在该键时，才会执行方法逻辑。
     * 如果缓存已存在，直接返回缓存结果，跳过方法体。
     *
     * @param songDTO 歌曲查询条件
     * @return 用户收藏的歌曲列表
     */
    @Override
    @Cacheable(key = "@userCacheKeys.key('songs', #songDTO)")
    public Result<PageResult<SongVO>> getUserFavoriteSongs(SongDTO songDTO) {
        Long userId = requireUser();

        // 获取用户收藏的歌曲 ID 列表
        List<Long> favoriteSongIds = userFavoriteMapper.getUserFavoriteSongIds(userId);
        if (favoriteSongIds.isEmpty()) {
            return Result.success(new PageResult<>(0L, Collections.emptyList()));
        }

        // 分页查询收藏的歌曲，支持模糊查询
        Page<SongVO> page = new Page<>(songDTO.getPageNum(), songDTO.getPageSize());
        IPage<SongVO> songPage = songMapper.getSongsByIds(
                page,
                userId,
                favoriteSongIds,
                songDTO.getSongName(),
                songDTO.getArtistName(),
                songDTO.getAlbum(),
                songDTO.getKeyword()
        );

        // 遍历结果，设置 likeStatus
        List<SongVO> songVOList = songPage.getRecords().stream()
                .peek(songVO -> songVO.setLikeStatus(LikeStatusEnum.LIKE.getId())) // 设置为已收藏
                .toList();

        songArtistService.attachArtists(songVOList);

        return Result.success(new PageResult<>(songPage.getTotal(), songVOList));
    }

    /**
     * 收藏歌曲
     *使用 @CacheEvict 清空多个缓存区域，确保数据一致性，但可能影响性能。
     * @param songId 歌曲 ID
     * @return 成功或失败
     */
    @Override
    @CacheEvict(cacheNames = {"userFavoriteCache", "songCache", "artistCache", "playlistCache"}, allEntries = true)
    public Result collectSong(Long songId) {
        Long userId = requireUser();

        requirePositiveId(songId);
        if (songMapper.selectById(songId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "歌曲不存在");
        }
        userFavoriteMapper.insertSongIfAbsent(userId, songId);

        return Result.success(MessageConstant.ADD + MessageConstant.SUCCESS);
    }

    /**
     * 取消收藏歌曲
     *
     * @param songId 歌曲 ID
     * @return 成功或失败
     */
    @Override
    @CacheEvict(cacheNames = {"userFavoriteCache", "songCache", "artistCache", "playlistCache"}, allEntries = true)
    public Result cancelCollectSong(Long songId) {
        Long userId = requireUser();
        requirePositiveId(songId);

        QueryWrapper<UserFavorite> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId).eq("type", 0).eq("song_id", songId);
        userFavoriteMapper.delete(queryWrapper);

        return Result.success(MessageConstant.DELETE + MessageConstant.SUCCESS);
    }

    /**
     * 获取用户收藏的歌单列表
     *
     * @param playlistDTO 歌单查询条件
     * @return 用户收藏的歌单列表
     */
    @Override
    // Playlist visibility is mutable, so always recheck it in the SQL query.
    public Result<PageResult<PlaylistVO>> getUserFavoritePlaylists(PlaylistDTO playlistDTO) {
        Long userId = requireUser();

        // 获取用户收藏的歌单 ID 列表
        List<Long> favoritePlaylistIds = userFavoriteMapper.getUserFavoritePlaylistIds(userId);
        if (favoritePlaylistIds.isEmpty()) {
            return Result.success(new PageResult<>(0L, Collections.emptyList()));
        }

        // 分页查询收藏的歌单，支持模糊查询
        Page<PlaylistVO> page = new Page<>(playlistDTO.getPageNum(), playlistDTO.getPageSize());
        IPage<PlaylistVO> playlistPage = playlistMapper.getPlaylistsByIds(
                userId,
                page,
                favoritePlaylistIds,
                playlistDTO.getTitle(),
                playlistDTO.getStyle()
        );

        return Result.success(new PageResult<>(playlistPage.getTotal(), playlistPage.getRecords()));
    }

    /**
     * 收藏歌单
     *
     * @param playlistId 歌单 ID
     * @return 成功或失败
     */
    @Override
    @CacheEvict(cacheNames = {"userFavoriteCache", "songCache", "artistCache", "playlistCache"}, allEntries = true)
    @Transactional
    public Result collectPlaylist(Long playlistId) {
        Long userId = requireUser();

        requirePositiveId(playlistId);
        // The row lock serializes a public -> private change with this write.
        playlistAccessService.lockReadable(playlistId);
        userFavoriteMapper.insertPlaylistIfAbsent(userId, playlistId);

        return Result.success(MessageConstant.ADD + MessageConstant.SUCCESS);
    }

    /**
     * 取消收藏歌单
     *
     * @param playlistId 歌单 ID
     * @return 成功或失败
     */
    @Override
    @CacheEvict(cacheNames = {"userFavoriteCache", "songCache", "artistCache", "playlistCache"}, allEntries = true)
    public Result cancelCollectPlaylist(Long playlistId) {
        Long userId = requireUser();
        requirePositiveId(playlistId);

        QueryWrapper<UserFavorite> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId).eq("type", 1).eq("playlist_id", playlistId);
        userFavoriteMapper.delete(queryWrapper);

        return Result.success(MessageConstant.DELETE + MessageConstant.SUCCESS);
    }

    private static Long requireUser() {
        Long userId = CurrentUser.id();
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        return userId;
    }

    private static void requirePositiveId(Long id) {
        if (id == null || id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "资源 ID 不合法");
    }

}
