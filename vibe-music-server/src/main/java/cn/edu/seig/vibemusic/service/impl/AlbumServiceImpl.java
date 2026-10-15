package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.constant.MediaCleanupSourceType;
import cn.edu.seig.vibemusic.constant.MessageConstant;
import cn.edu.seig.vibemusic.enumeration.ArtistStatusEnum;
import cn.edu.seig.vibemusic.enumeration.LikeStatusEnum;
import cn.edu.seig.vibemusic.enumeration.RoleEnum;
import cn.edu.seig.vibemusic.mapper.AlbumMapper;
import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.mapper.SongMapper;
import cn.edu.seig.vibemusic.mapper.UserFavoriteMapper;
import cn.edu.seig.vibemusic.model.dto.AlbumAddDTO;
import cn.edu.seig.vibemusic.model.dto.AlbumQueryDTO;
import cn.edu.seig.vibemusic.model.dto.AlbumUpdateDTO;
import cn.edu.seig.vibemusic.model.entity.Album;
import cn.edu.seig.vibemusic.model.entity.Artist;
import cn.edu.seig.vibemusic.model.entity.Song;
import cn.edu.seig.vibemusic.model.entity.UserFavorite;
import cn.edu.seig.vibemusic.model.vo.AlbumDetailVO;
import cn.edu.seig.vibemusic.model.vo.AlbumVO;
import cn.edu.seig.vibemusic.model.vo.SongVO;
import cn.edu.seig.vibemusic.result.PageResult;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.IAlbumService;
import cn.edu.seig.vibemusic.service.MediaCleanupService;
import cn.edu.seig.vibemusic.service.SongArtistService;
import cn.edu.seig.vibemusic.util.JwtUtil;
import cn.edu.seig.vibemusic.util.SortWhitelistUtil;
import cn.edu.seig.vibemusic.util.TypeConversionUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.time.LocalDateTime;

/**
 * 用户端专辑业务实现。
 */
@Service
public class AlbumServiceImpl extends ServiceImpl<AlbumMapper, Album> implements IAlbumService {

    private static final int MAX_LIMIT = 50;

    @Autowired
    private AlbumMapper albumMapper;

    @Autowired
    private UserFavoriteMapper userFavoriteMapper;

    @Autowired
    private SongMapper songMapper;

    @Autowired
    private ArtistMapper artistMapper;

    @Autowired
    private MediaCleanupService mediaCleanupService;
    @Autowired
    private SongArtistService songArtistService;

    /**
     * 主艺人必须存在且处于启用状态：停用（软删除）的歌手不能再被新专辑引用，
     * 否则专辑列表会出现指向已合并组合歌手的脏引用。
     */
    private boolean isActiveArtist(Long artistId) {
        if (artistId == null) {
            return false;
        }
        Artist artist = artistMapper.selectById(artistId);
        return artist != null && ArtistStatusEnum.isActive(artist.getStatus());
    }

    @Override
    public Result<PageResult<AlbumVO>> getAlbumList(AlbumQueryDTO queryDTO) {
        Page<AlbumVO> page = new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize());
        // 排序：仅接受白名单字段（releaseDate/songCount/createdAt 为 SELECT 别名，GROUP BY 查询可按别名排序），
        // 未命中/缺省时按「发行日期倒序、空值靠后」的历史行为
        String orderBy = SortWhitelistUtil.toOrderBy(
                queryDTO.getSortField(), queryDTO.getOrder(),
                Map.of(
                        "albumId", "a.id",
                        "albumName", "CONVERT(a.name USING gbk)", // 拼音排序：中文按拼音、英文按字母
                        "releaseDate", "releaseDate",
                        "songCount", "songCount",
                        "createdAt", "createdAt"
                ),
                "releaseDate IS NULL, releaseDate DESC, a.id DESC");
        IPage<AlbumVO> albumPage = albumMapper.getAlbumList(
                page,
                queryDTO.getAlbumName(),
                queryDTO.getArtistId(),
                queryDTO.getKeyword(),
                queryDTO.getStatus(),
                queryDTO.getExcludeDisabledArtist(),
                orderBy
        );

        return Result.success(new PageResult<>(albumPage.getTotal(), albumPage.getRecords()));
    }

    @Override
    public Result<AlbumDetailVO> getAlbumDetail(Long albumId, HttpServletRequest request) {
        Album entity = albumMapper.selectById(albumId);
        if (entity == null) {
            return Result.error(MessageConstant.ALBUM + MessageConstant.NOT_FOUND);
        }

        AlbumDetailVO album = albumMapper.getAlbumDetailById(albumId);
        List<SongVO> songs = albumMapper.getSongsByAlbumId(albumId);
        setSongLikeStatus(songs, request);
        songArtistService.attachArtists(songs);
        album.setSongs(songs);
        album.setSongCount((long) songs.size());
        return Result.success(album);
    }

    @Override
    public Result<List<SongVO>> getAlbumSongs(Long albumId, HttpServletRequest request) {
        if (albumMapper.selectById(albumId) == null) {
            return Result.error(MessageConstant.ALBUM + MessageConstant.NOT_FOUND);
        }

        List<SongVO> songs = albumMapper.getSongsByAlbumId(albumId);
        setSongLikeStatus(songs, request);
        songArtistService.attachArtists(songs);
        return Result.success(songs);
    }

    @Override
    public Result<List<AlbumVO>> searchAlbums(String keyword, int limit) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        if (normalizedKeyword.isEmpty()) {
            return Result.success(Collections.emptyList());
        }

        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        return Result.success(albumMapper.searchAlbums(normalizedKeyword, safeLimit));
    }

    @Override
    public Result<Boolean> albumExists(Long artistId, String albumName) {
        if (artistId == null || albumName == null || albumName.isBlank()) {
            return Result.success(false);
        }

        Long count = albumMapper.selectCount(new QueryWrapper<Album>()
                .eq("artist_id", artistId)
                .eq("name", albumName.trim()));
        return Result.success(count != null && count > 0);
    }

    @Override
    public Result<Long> getAllAlbumsCount() {
        return Result.success(albumMapper.getVisibleAlbumCount());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result addAlbum(AlbumAddDTO albumAddDTO) {
        if (albumAddDTO == null || albumAddDTO.getArtistId() == null
                || albumAddDTO.getAlbumName() == null || albumAddDTO.getAlbumName().isBlank()) {
            return Result.error("专辑名称和主艺人不能为空");
        }
        if (!isActiveArtist(albumAddDTO.getArtistId())) {
            return Result.error("主艺人不存在");
        }
        String albumName = albumAddDTO.getAlbumName().trim();
        if (albumExistsForArtist(albumAddDTO.getArtistId(), albumName, null)) {
            return Result.error(MessageConstant.ALBUM + MessageConstant.ALREADY_EXISTS);
        }
        String status = normalizeStatus(albumAddDTO.getStatus(), "DRAFT");
        Album album = new Album()
                .setArtistId(albumAddDTO.getArtistId())
                .setAlbumName(albumName)
                .setCoverUrl(trimToNull(albumAddDTO.getCoverUrl()))
                .setReleaseDate(albumAddDTO.getReleaseDate())
                .setDescription(albumAddDTO.getDescription())
                .setStatus(status)
                .setCreatedAt(LocalDateTime.now())
                .setUpdatedAt(LocalDateTime.now());
        if (albumMapper.insert(album) == 0) {
            return Result.error(MessageConstant.ADD + MessageConstant.FAILED);
        }
        return Result.success(MessageConstant.ADD + MessageConstant.SUCCESS);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result updateAlbum(AlbumUpdateDTO albumUpdateDTO) {
        Album albumInDB = albumMapper.selectById(albumUpdateDTO.getAlbumId());
        if (albumInDB == null) {
            return Result.error(MessageConstant.ALBUM + MessageConstant.NOT_FOUND);
        }
        if (albumUpdateDTO.getAlbumName() == null || albumUpdateDTO.getAlbumName().isBlank()) {
            return Result.error(MessageConstant.ALBUM + MessageConstant.NOT_NULL);
        }

        String albumName = albumUpdateDTO.getAlbumName().trim();
        Long artistId = albumUpdateDTO.getArtistId() == null ? albumInDB.getArtistId() : albumUpdateDTO.getArtistId();
        if (!isActiveArtist(artistId)) {
            return Result.error("主艺人不存在");
        }
        if (albumExistsForArtist(artistId, albumName, albumInDB.getAlbumId())) {
            return Result.error(MessageConstant.ALBUM + MessageConstant.ALREADY_EXISTS);
        }

        UpdateWrapper<Album> albumUpdate = new UpdateWrapper<Album>()
                .eq("id", albumInDB.getAlbumId())
                .set("artist_id", artistId)
                .set("name", albumName)
                .set("release_date", albumUpdateDTO.getReleaseDate())
                .set("description", albumUpdateDTO.getDescription())
                .set("status", normalizeStatus(albumUpdateDTO.getStatus(), albumInDB.getStatus() == null ? "DRAFT" : albumInDB.getStatus()))
                .set("updated_at", LocalDateTime.now());
        // 编辑表单不包含封面字段：未传 coverUrl 时保留原封面，避免「编辑专辑」把封面清空
        if (albumUpdateDTO.getCoverUrl() != null) {
            albumUpdate.set("cover_url", trimToNull(albumUpdateDTO.getCoverUrl()));
        }
        int updated = albumMapper.update(null, albumUpdate);
        if (updated == 0) {
            return Result.error(MessageConstant.UPDATE + MessageConstant.FAILED);
        }

        if (!albumName.equals(albumInDB.getAlbumName())) {
            songMapper.update(null, new UpdateWrapper<Song>()
                    .eq("album_id", albumInDB.getAlbumId())
                    .set("album", albumName));
        }
        return Result.success(MessageConstant.UPDATE + MessageConstant.SUCCESS);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result updateAlbumCover(Long albumId, String coverUrl) {
        Album album = albumMapper.selectById(albumId);
        if (album == null) {
            return Result.error(MessageConstant.ALBUM + MessageConstant.NOT_FOUND);
        }

        String oldCoverUrl = album.getCoverUrl();
        album.setCoverUrl(coverUrl);
        if (albumMapper.updateById(album) == 0) {
            return Result.error(MessageConstant.UPDATE + MessageConstant.FAILED);
        }
        // 旧封面只登记、不就地删：它很可能同时是专辑内歌曲的封面，删之前必须核对引用
        mediaCleanupService.enqueueForId(oldCoverUrl, MediaCleanupSourceType.ALBUM_COVER_REPLACE, albumId, "替换专辑封面");
        return Result.success(MessageConstant.UPDATE + MessageConstant.SUCCESS);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(cacheNames = "songCache", allEntries = true)
    public Result deleteAlbum(Long albumId) {
        return deleteAlbum(albumId, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(cacheNames = "songCache", allEntries = true)
    public Result deleteAlbum(Long albumId, boolean deleteSongs) {
        Album album = albumMapper.selectById(albumId);
        if (album == null) {
            return Result.error(MessageConstant.ALBUM + MessageConstant.NOT_FOUND);
        }

        List<Song> songs = songMapper.selectList(new QueryWrapper<Song>().eq("album_id", albumId));

        if (deleteSongs) {
            // 连同专辑内的所有歌曲一起删除：只删记录，音频与封面的清理登记到任务表。
            // tb_comment / tb_genre / tb_playlist_binding / tb_user_favorite 均对 tb_song 设置了 ON DELETE CASCADE，
            // 删除歌曲记录时相关评论、收藏、歌单关联会自动清理。
            if (!songs.isEmpty()) {
                songMapper.deleteByIds(songs.stream().map(Song::getSongId).collect(Collectors.toList()));
            }
        } else {
            // 删除专辑只解除歌曲归属，歌曲、音频、歌单、收藏和评论均保留。
            songMapper.update(null, new UpdateWrapper<Song>()
                    .eq("album_id", albumId)
                    .set("album_id", null));
        }

        if (albumMapper.deleteById(albumId) == 0) {
            return Result.error(MessageConstant.DELETE + MessageConstant.FAILED);
        }

        // 记录删完才登记清理：worker 核对引用时这些行已经消失，
        // 因此仍被别处引用的对象（例如保留歌曲共用的专辑封面）会被判定 RETAINED 而不删。
        if (deleteSongs) {
            for (Song song : songs) {
                mediaCleanupService.enqueueForId(song.getCoverUrl(), MediaCleanupSourceType.ALBUM_DELETE,
                        song.getSongId(), "删除专辑时连带删除歌曲封面");
                mediaCleanupService.enqueueForId(song.getAudioUrl(), MediaCleanupSourceType.ALBUM_DELETE,
                        song.getSongId(), "删除专辑时连带删除歌曲音频");
            }
        }
        mediaCleanupService.enqueueForId(album.getCoverUrl(), MediaCleanupSourceType.ALBUM_DELETE,
                albumId, "删除专辑封面");
        return Result.success(MessageConstant.DELETE + MessageConstant.SUCCESS);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result deleteAlbums(List<Long> albumIds) {
        if (albumIds == null || albumIds.isEmpty()) {
            return Result.error(MessageConstant.DATA_NOT_FOUND);
        }

        List<Album> albums = albumMapper.selectByIds(albumIds);
        for (Album album : albums) {
            songMapper.update(null, new UpdateWrapper<Song>()
                    .eq("album_id", album.getAlbumId())
                    .set("album_id", null));
        }

        if (albumMapper.deleteByIds(albumIds) == 0) {
            return Result.error(MessageConstant.DELETE + MessageConstant.FAILED);
        }
        for (Album album : albums) {
            mediaCleanupService.enqueueForId(album.getCoverUrl(), MediaCleanupSourceType.ALBUM_DELETE,
                    album.getAlbumId(), "批量删除专辑封面");
        }
        return Result.success(MessageConstant.DELETE + MessageConstant.SUCCESS);
    }

    private boolean albumExistsForArtist(Long artistId, String albumName, Long excludeAlbumId) {
        QueryWrapper<Album> queryWrapper = new QueryWrapper<Album>()
                .eq("artist_id", artistId)
                .eq("name", albumName);
        if (excludeAlbumId != null) {
            queryWrapper.ne("id", excludeAlbumId);
        }
        return albumMapper.selectCount(queryWrapper) > 0;
    }

    private boolean hasSongs(Long albumId) {
        return songMapper.selectCount(new QueryWrapper<Song>().eq("album_id", albumId)) > 0;
    }

    private String normalizeStatus(String status, String fallback) {
        String normalized = status == null ? "" : status.trim().toUpperCase();
        if (normalized.isEmpty()) {
            return fallback;
        }
        if (!"DRAFT".equals(normalized) && !"PUBLISHED".equals(normalized)) {
            throw new IllegalArgumentException("专辑状态仅支持 DRAFT 或 PUBLISHED");
        }
        return normalized;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void setSongLikeStatus(List<SongVO> songs, HttpServletRequest request) {
        songs.forEach(song -> song.setLikeStatus(LikeStatusEnum.DEFAULT.getId()));

        Long userId = getUserId(request);
        if (userId == null || songs.isEmpty()) {
            return;
        }

        List<UserFavorite> favoriteSongs = userFavoriteMapper.selectList(new QueryWrapper<UserFavorite>()
                .eq("user_id", userId)
                .eq("type", 0));
        Set<Long> favoriteSongIds = favoriteSongs.stream()
                .map(UserFavorite::getSongId)
                .collect(Collectors.toSet());

        songs.forEach(song -> {
            if (favoriteSongIds.contains(song.getSongId())) {
                song.setLikeStatus(LikeStatusEnum.LIKE.getId());
            }
        });
    }

    private Long getUserId(HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        if (token == null || token.isEmpty()) {
            return null;
        }
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        if (token.isEmpty()) {
            return null;
        }

        try {
            Map<String, Object> claims = JwtUtil.parseToken(token);
            String role = (String) claims.get(JwtClaimsConstant.ROLE);
            if (!RoleEnum.USER.getRole().equals(role)) {
                return null;
            }
            return TypeConversionUtil.toLong(claims.get(JwtClaimsConstant.USER_ID));
        } catch (Exception ignored) {
            return null;
        }
    }
}
