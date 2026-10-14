package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.constant.MessageConstant;
import cn.edu.seig.vibemusic.enumeration.PlaylistStatusEnum;
import cn.edu.seig.vibemusic.mapper.PlaylistBindingMapper;
import cn.edu.seig.vibemusic.mapper.PlaylistMapper;
import cn.edu.seig.vibemusic.mapper.SongMapper;
import cn.edu.seig.vibemusic.mapper.UserFavoriteMapper;
import cn.edu.seig.vibemusic.model.dto.AdminPlaylistQueryDTO;
import cn.edu.seig.vibemusic.model.dto.PlaylistAddDTO;
import cn.edu.seig.vibemusic.model.dto.PlaylistDTO;
import cn.edu.seig.vibemusic.model.dto.PlaylistSongsDTO;
import cn.edu.seig.vibemusic.model.dto.PlaylistUpdateDTO;
import cn.edu.seig.vibemusic.model.entity.Playlist;
import cn.edu.seig.vibemusic.model.entity.Song;
import cn.edu.seig.vibemusic.model.entity.UserFavorite;
import cn.edu.seig.vibemusic.model.vo.AdminPlaylistVO;
import cn.edu.seig.vibemusic.model.vo.PlaylistDetailVO;
import cn.edu.seig.vibemusic.model.vo.PlaylistVO;
import cn.edu.seig.vibemusic.result.PageResult;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.security.PlaylistAccessService;
import cn.edu.seig.vibemusic.service.IPlaylistService;
import cn.edu.seig.vibemusic.service.MinioService;
import cn.edu.seig.vibemusic.service.SongArtistService;
import cn.edu.seig.vibemusic.util.SortWhitelistUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PlaylistServiceImpl extends ServiceImpl<PlaylistMapper, Playlist> implements IPlaylistService {
    /** 管理员列表来源筛选：只看平台歌单 */
    private static final String SOURCE_PLATFORM = "PLATFORM";
    /** 管理员列表来源筛选：只看用户自建歌单 */
    private static final String SOURCE_USER = "USER";

    @Autowired private PlaylistMapper playlistMapper;
    @Autowired private PlaylistBindingMapper playlistBindingMapper;
    @Autowired private SongMapper songMapper;
    @Autowired private UserFavoriteMapper userFavoriteMapper;
    @Autowired private MinioService minioService;
    @Autowired private PlaylistAccessService playlistAccessService;
    @Autowired private SongArtistService songArtistService;

    // Privacy is mutable. Read it from the database rather than a shared response cache.
    // viewerId lets the SQL push the caller's own playlists to the end and mark favourites.
    @Override
    public Result<PageResult<PlaylistVO>> getAllPlaylists(PlaylistDTO query) {
        Long viewerId = PlaylistAccessService.optionalUserId();
        var page = playlistMapper.getPublicPlaylists(
                new Page<>(query.getPageNum(), query.getPageSize()),
                viewerId, query.getTitle(), query.getStyle());
        return Result.success(new PageResult<>(page.getTotal(), page.getRecords()));
    }

    @Override
    public Result<PageResult<AdminPlaylistVO>> getAllPlaylistsInfo(AdminPlaylistQueryDTO query) {
        PlaylistAccessService.requireAdmin();
        if (query == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "查询条件不能为空");
        String status = normalizeStatus(query.getStatus());
        Boolean platform = parseSource(query.getSource());
        // 排序：仅接受白名单字段，未命中/缺省时按编号倒序（历史行为）。
        // songCount 是 SELECT 里的子查询别名，MySQL 允许直接按别名排序。
        String orderBy = SortWhitelistUtil.toOrderBy(
                query.getSortField(), query.getOrder(),
                Map.of("playlistId", "p.id", "songCount", "songCount"),
                "p.id DESC");
        var page = playlistMapper.getAdminPlaylists(
                new Page<>(query.getPageNum(), query.getPageSize()),
                query.getTitle(), query.getStyle(), status, platform, orderBy);
        return Result.success(new PageResult<>(page.getTotal(), page.getRecords()));
    }

    /** Rejects unknown status values instead of silently returning an empty page. */
    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) return null;
        PlaylistStatusEnum parsed = PlaylistStatusEnum.parse(status);
        if (parsed == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "发布状态只能是 DRAFT 或 PUBLISHED");
        return parsed.getStatus();
    }

    /** PLATFORM / USER -> platform flag; null means both. */
    private Boolean parseSource(String source) {
        if (source == null || source.isBlank()) return null;
        if (SOURCE_PLATFORM.equalsIgnoreCase(source.trim())) return Boolean.TRUE;
        if (SOURCE_USER.equalsIgnoreCase(source.trim())) return Boolean.FALSE;
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "来源只能是 PLATFORM 或 USER");
    }

    private QueryWrapper<Playlist> query(PlaylistDTO query) {
        return new QueryWrapper<Playlist>()
                .like(query.getTitle() != null && !query.getTitle().isBlank(), "title", query.getTitle())
                .eq(query.getStyle() != null && !query.getStyle().isBlank(), "style", query.getStyle());
    }

    @Override
    public Result<List<PlaylistVO>> getRecommendedPlaylists(HttpServletRequest request) {
        Long userId = PlaylistAccessService.optionalUserId();
        List<PlaylistVO> recommendations = new ArrayList<>();
        if (userId != null) {
            List<Long> favoriteIds = userFavoriteMapper.getFavoritePlaylistIdsByUserId(userId);
            if (!favoriteIds.isEmpty()) {
                List<String> styles = playlistMapper.getFavoritePlaylistStyles(favoriteIds).stream()
                        .filter(Objects::nonNull).filter(s -> !s.isBlank()).distinct().toList();
                if (!styles.isEmpty()) recommendations.addAll(
                        playlistMapper.getRecommendedPlaylistsByStyles(styles, favoriteIds, 10));
            }
        }
        if (recommendations.size() < 10) {
            Set<Long> present = recommendations.stream().map(PlaylistVO::getPlaylistId).collect(Collectors.toSet());
            for (PlaylistVO item : playlistMapper.getRandomPlaylists(10)) {
                if (recommendations.size() == 10) break;
                if (present.add(item.getPlaylistId())) recommendations.add(item);
            }
        }
        return Result.success(recommendations);
    }

    @Override
    public Result<PlaylistDetailVO> getPlaylistDetail(Long playlistId, HttpServletRequest request) {
        Long userId = PlaylistAccessService.optionalUserId();
        PlaylistDetailVO detail = playlistMapper.getPlaylistDetailById(
                PlaylistAccessService.positiveId(playlistId), userId);
        if (detail == null) throw PlaylistAccessService.notFound();
        detail.setEditable(userId != null && userId.equals(detail.getOwnerUserId()));
        detail.setLikeStatus(0);
        if (detail.getSongs() == null) detail.setSongs(new ArrayList<>());
        if (detail.getComments() == null) detail.setComments(new ArrayList<>());
        Set<Long> favorites = new HashSet<>();
        if (userId != null) {
            if (userFavoriteMapper.selectOne(new QueryWrapper<UserFavorite>()
                    .eq("user_id", userId).eq("type", 1).eq("playlist_id", playlistId)) != null)
                detail.setLikeStatus(1);
            favorites.addAll(userFavoriteMapper.getFavoriteSongIdsByUserId(userId));
        }
        detail.getSongs().forEach(song -> song.setLikeStatus(favorites.contains(song.getSongId()) ? 1 : 0));
        songArtistService.attachArtists(detail.getSongs());
        return Result.success(detail);
    }

    @Override
    public Result<PlaylistDetailVO> getAdminPlaylistDetail(Long playlistId) {
        PlaylistAccessService.requireAdmin();
        return Result.success(adminDetail(PlaylistAccessService.positiveId(playlistId)));
    }

    /** Admin detail ignores publication state and visibility, and covers user-owned playlists too. */
    private PlaylistDetailVO adminDetail(Long playlistId) {
        PlaylistDetailVO detail = playlistMapper.getAdminPlaylistDetailById(playlistId);
        if (detail == null) throw PlaylistAccessService.notFound();
        detail.setEditable(false);
        detail.setLikeStatus(0);
        if (detail.getSongs() == null) detail.setSongs(new ArrayList<>());
        if (detail.getComments() == null) detail.setComments(new ArrayList<>());
        songArtistService.attachArtists(detail.getSongs());
        return detail;
    }

    @Override
    public Result<Long> getAllPlaylistsCount(String style) {
        PlaylistAccessService.requireAdmin();
        return Result.success(playlistMapper.selectCount(new QueryWrapper<Playlist>()
                .isNull("owner_user_id").eq(style != null && !style.isBlank(), "style", style)));
    }

    @Override
    @Transactional
    public Result<PlaylistDetailVO> addPlaylist(PlaylistAddDTO input) {
        PlaylistAccessService.requireAdmin();
        if (input == null || input.getTitle() == null || input.getTitle().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "歌单名称不能为空");
        String title = input.getTitle().trim();
        if (platformTitleExists(title, null)) return Result.error(MessageConstant.PLAYLIST + MessageConstant.ALREADY_EXISTS);
        List<Long> songIds = normalizeIds(input.getSongIds());
        requireSongs(songIds);
        Playlist playlist = new Playlist()
                .setTitle(title)
                .setIntroduction(normalize(input.getIntroduction()))
                .setStyle(normalize(input.getStyle()))
                .setOwnerUserId(null)
                // Platform playlists are always public; "not live yet" is expressed by the draft state.
                .setIsPublic(true)
                .setStatus(PlaylistStatusEnum.DRAFT.getStatus())
                .setCoverIsCustom(false);
        playlistMapper.insert(playlist);
        for (Long songId : songIds) playlistBindingMapper.addSongIfAbsent(playlist.getPlaylistId(), songId);
        return Result.success(adminDetail(playlist.getPlaylistId()));
    }

    @Override
    @Transactional
    public Result<PlaylistDetailVO> updatePlaylist(PlaylistUpdateDTO input) {
        if (input == null || input.getPlaylistId() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "歌单 id 不能为空");
        Playlist playlist = platformForUpdate(input.getPlaylistId());
        if (input.getTitle() == null || input.getTitle().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "歌单名称不能为空");
        String title = input.getTitle().trim();
        if (platformTitleExists(title, input.getPlaylistId())) return Result.error(MessageConstant.PLAYLIST + MessageConstant.ALREADY_EXISTS);
        playlist.setTitle(title)
                .setIntroduction(normalize(input.getIntroduction()))
                .setStyle(normalize(input.getStyle()))
                .setIsPublic(true);
        playlistMapper.updateById(playlist);
        return Result.success(adminDetail(playlist.getPlaylistId()));
    }

    @Override
    @Transactional
    public Result<PlaylistDetailVO> publishPlaylist(Long playlistId) {
        Playlist playlist = platformForUpdate(playlistId);
        // A platform playlist is never private: publishing always makes it public.
        playlist.setIsPublic(true).setStatus(PlaylistStatusEnum.PUBLISHED.getStatus());
        playlistMapper.updateById(playlist);
        return Result.success(adminDetail(playlist.getPlaylistId()));
    }

    @Override
    @Transactional
    public Result<PlaylistDetailVO> unpublishPlaylist(Long playlistId) {
        Playlist playlist = platformForUpdate(playlistId);
        playlist.setStatus(PlaylistStatusEnum.DRAFT.getStatus());
        playlistMapper.updateById(playlist);
        return Result.success(adminDetail(playlist.getPlaylistId()));
    }

    @Override
    @Transactional
    public Result<PlaylistDetailVO> addPlaylistSongs(Long playlistId, PlaylistSongsDTO input) {
        Playlist playlist = platformForUpdate(playlistId);
        List<Long> songIds = normalizeIds(input == null ? null : input.getSongIds());
        if (songIds.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少选择一首歌曲");
        requireSongs(songIds);
        for (Long songId : songIds) playlistBindingMapper.addSongIfAbsent(playlist.getPlaylistId(), songId);
        return Result.success(adminDetail(playlist.getPlaylistId()));
    }

    @Override
    @Transactional
    public Result<PlaylistDetailVO> removePlaylistSong(Long playlistId, Long songId) {
        Playlist playlist = platformForUpdate(playlistId);
        playlistBindingMapper.removeSong(playlist.getPlaylistId(), PlaylistAccessService.positiveId(songId));
        return Result.success(adminDetail(playlist.getPlaylistId()));
    }

    private List<Long> normalizeIds(List<Long> ids) {
        return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().sorted().toList();
    }

    private void requireSongs(List<Long> ids) {
        if (!ids.isEmpty() && songMapper.selectCount(new QueryWrapper<Song>().in("id", ids)) != ids.size())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "部分歌曲不存在，歌单未修改");
    }

    private String normalize(String value) { return value == null ? "" : value.trim(); }

    private boolean platformTitleExists(String title, Long exceptId) {
        return playlistMapper.selectCount(new QueryWrapper<Playlist>().isNull("owner_user_id")
                .eq("title", title).ne(exceptId != null, "id", exceptId)) > 0;
    }

    @Override
    @Transactional
    public Result updatePlaylistCover(Long playlistId, String coverUrl) {
        Playlist playlist = platformForUpdate(playlistId);
        String oldCover = playlist.getCoverUrl();
        boolean oldWasUploaded = Boolean.TRUE.equals(playlist.getCoverIsCustom());
        // Platform covers are always curated by an administrator, never derived from a song.
        playlist.setCoverUrl(coverUrl).setCoverIsCustom(true);
        playlistMapper.updateById(playlist);
        if (!Objects.equals(oldCover, coverUrl)) deleteCoverAfterCommit(oldCover, oldWasUploaded);
        return Result.success();
    }

    @Override
    @Transactional
    public Result deletePlaylist(Long playlistId) {
        Playlist playlist = adminLock(playlistId);
        playlistMapper.deleteById(playlistId);
        deleteCoverAfterCommit(playlist.getCoverUrl(), Boolean.TRUE.equals(playlist.getCoverIsCustom()));
        return Result.success();
    }

    @Override
    @Transactional
    public Result deletePlaylists(List<Long> playlistIds) {
        PlaylistAccessService.requireAdmin();
        if (playlistIds == null || playlistIds.isEmpty() || playlistIds.size() > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每次请选择 1–100 个歌单");
        playlistIds.forEach(PlaylistAccessService::positiveId);
        List<Playlist> targets = playlistIds.stream().distinct().sorted().map(this::adminLock).toList();
        for (Playlist target : targets) {
            playlistMapper.deleteById(target.getPlaylistId());
            deleteCoverAfterCommit(target.getCoverUrl(), Boolean.TRUE.equals(target.getCoverIsCustom()));
        }
        return Result.success();
    }

    /** Metadata edits stay restricted to platform playlists. */
    private Playlist platformForUpdate(Long id) {
        return playlistAccessService.lockPlatform(id);
    }

    /**
     * Deletion covers user-owned playlists as well, so an administrator can remove content
     * that was never created through the admin console. Bindings, favourites and comments
     * are removed by the existing cascading foreign keys.
     */
    private Playlist adminLock(Long id) {
        PlaylistAccessService.requireAdmin();
        Playlist playlist = playlistMapper.lockById(PlaylistAccessService.positiveId(id));
        if (playlist == null) throw PlaylistAccessService.notFound();
        return playlist;
    }

    private void deleteCoverAfterCommit(String cover, boolean uploaded) {
        // A default cover is a shared song image; removing it would break the song itself.
        if (!uploaded || cover == null || cover.isBlank()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { minioService.deleteFile(cover); }
                catch (RuntimeException e) { log.warn("Playlist cover cleanup failed after commit"); }
            }
        });
    }
}
