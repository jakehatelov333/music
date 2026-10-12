package cn.edu.seig.vibemusic.service;

import cn.edu.seig.vibemusic.enumeration.PlaylistStatusEnum;
import cn.edu.seig.vibemusic.mapper.*;
import cn.edu.seig.vibemusic.model.dto.*;
import cn.edu.seig.vibemusic.model.entity.Playlist;
import cn.edu.seig.vibemusic.model.entity.PlaylistCreateRequest;
import cn.edu.seig.vibemusic.model.entity.Song;
import cn.edu.seig.vibemusic.model.vo.PlaylistDetailVO;
import cn.edu.seig.vibemusic.model.vo.PlaylistVO;
import cn.edu.seig.vibemusic.result.PageResult;
import cn.edu.seig.vibemusic.security.PlaylistAccessService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Business operations shared by HTTP and future confirmed Agent actions. Identity is server supplied. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonalPlaylistService {
    /** Cover uploads are small square images; anything larger is rejected before touching object storage. */
    private static final long MAX_COVER_BYTES = 5L * 1024 * 1024;
    private static final String COVER_FOLDER = "playlists";

    private final PlaylistMapper playlists;
    private final PlaylistBindingMapper bindings;
    private final PlaylistCreateRequestMapper requests;
    private final SongMapper songs;
    private final PlaylistAccessService access;
    private final IPlaylistService playlistDetails;
    private final ObjectMapper json;
    private final Validator validator;
    private final MinioService minioService;

    public PageResult<PlaylistVO> mine(PlaylistDTO query) {
        Long userId = PlaylistAccessService.requireUserId();
        validate(query);
        var result = playlists.getOwnedPlaylists(new Page<>(query.getPageNum(), query.getPageSize()),
                userId, query.getTitle(), query.getStyle());
        return new PageResult<>(result.getTotal(), result.getRecords());
    }

    @Transactional
    public PlaylistDetailVO create(String requestKey, PersonalPlaylistCreateDTO input) {
        Long userId = PlaylistAccessService.requireUserId();
        validate(input);
        if (requestKey == null || !requestKey.matches("[A-Za-z0-9_-]{16,64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要有效的 Idempotency-Key（16–64 位字母、数字、横线或下划线）");
        List<Long> ids = normalizeIds(input.getSongIds());
        String fingerprint = fingerprint(input, ids);
        requests.reserve(userId, requestKey, fingerprint);
        PlaylistCreateRequest receipt = requests.lock(userId, requestKey);
        if (!fingerprint.equals(receipt.getRequestHash()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该请求标识已用于不同的创建内容，请使用新的请求标识");
        if (receipt.getPlaylistId() != null) {
            // The durable receipt prevents replay from recreating a resource that was later deleted.
            Playlist existing = playlists.lockById(receipt.getPlaylistId());
            if (existing == null) throw new ResponseStatusException(HttpStatus.GONE, "该请求创建的歌单已删除，请使用新的请求标识");
            return detail(existing.getPlaylistId());
        }

        requireSongs(ids);
        Playlist playlist = metadata(new Playlist(), input)
                .setOwnerUserId(userId)
                // User playlists are never drafts: visibility alone decides who may see them.
                .setStatus(PlaylistStatusEnum.PUBLISHED.getStatus())
                .setCoverIsCustom(false);
        playlists.insert(playlist);
        for (Long id : ids) bindings.addSongIfAbsent(playlist.getPlaylistId(), id);
        applyDefaultCover(playlist);
        requests.complete(userId, requestKey, playlist.getPlaylistId());
        return detail(playlist.getPlaylistId());
    }

    @Transactional
    public PlaylistDetailVO update(Long playlistId, PersonalPlaylistDTO input) {
        Playlist playlist = access.lockOwned(playlistId);
        validate(input);
        playlists.updateById(metadata(playlist, input));
        return detail(playlistId);
    }

    @Transactional
    public void delete(Long playlistId) {
        Long userId = PlaylistAccessService.requireUserId();
        Playlist playlist = playlists.lockById(PlaylistAccessService.positiveId(playlistId));
        if (playlist == null) return;
        if (!userId.equals(playlist.getOwnerUserId())) throw PlaylistAccessService.notFound();
        // Existing schema cascades bindings, favorites and comments atomically.
        playlists.deleteById(playlistId);
        // Only an uploaded cover belongs to this playlist; a default cover is a shared song image.
        deleteCoverAfterCommit(playlist);
    }

    @Transactional
    public PlaylistDetailVO addSongs(Long playlistId, PlaylistSongsDTO input) {
        Playlist playlist = access.lockOwned(playlistId);
        validate(input);
        List<Long> ids = normalizeIds(input.getSongIds());
        requireSongs(ids);
        for (Long id : ids) bindings.addSongIfAbsent(playlistId, id);
        applyDefaultCover(playlist);
        return detail(playlistId);
    }

    @Transactional
    public PlaylistDetailVO removeSong(Long playlistId, Long songId) {
        access.lockOwned(playlistId);
        bindings.removeSong(playlistId, PlaylistAccessService.positiveId(songId));
        return detail(playlistId);
    }

    /**
     * Replaces the playlist cover with an uploaded image.
     * The cover is marked custom so later song additions never overwrite it.
     */
    @Transactional
    public PlaylistDetailVO updateCover(Long playlistId, MultipartFile cover) {
        Playlist playlist = access.lockOwned(playlistId);
        validateCover(cover);
        String previous = playlist.getCoverUrl();
        boolean previousWasCustom = Boolean.TRUE.equals(playlist.getCoverIsCustom());
        String coverUrl = minioService.uploadFile(cover, COVER_FOLDER);
        if (coverUrl == null || coverUrl.isBlank())
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "封面上传失败，请稍后重试");
        playlist.setCoverUrl(coverUrl).setCoverIsCustom(true);
        playlists.updateById(playlist);
        // The old object is removed only after the new cover is committed, and only when we uploaded it.
        if (previousWasCustom && !Objects.equals(previous, coverUrl)) deleteObjectAfterCommit(previous);
        return detail(playlistId);
    }

    /** Restores the automatic cover derived from the first song. */
    @Transactional
    public PlaylistDetailVO resetCover(Long playlistId) {
        Playlist playlist = access.lockOwned(playlistId);
        String previous = playlist.getCoverUrl();
        boolean previousWasCustom = Boolean.TRUE.equals(playlist.getCoverIsCustom());
        String derived = playlists.getFirstBoundSongCover(playlistId);
        playlist.setCoverUrl(derived).setCoverIsCustom(false);
        playlists.updateById(playlist);
        if (previousWasCustom && !Objects.equals(previous, derived)) deleteObjectAfterCommit(previous);
        return detail(playlistId);
    }

    /**
     * Derives the default cover from the playlist's first song with a cover.
     * Skipped once the owner uploaded a cover by hand, so a custom cover is never overwritten.
     */
    private void applyDefaultCover(Playlist playlist) {
        if (Boolean.TRUE.equals(playlist.getCoverIsCustom())) return;
        if (playlist.getCoverUrl() != null && !playlist.getCoverUrl().isBlank()) return;
        String derived = playlists.getFirstBoundSongCover(playlist.getPlaylistId());
        if (derived == null || derived.isBlank()) return;
        playlist.setCoverUrl(derived);
        playlists.updateById(playlist);
    }

    private void validateCover(MultipartFile cover) {
        if (cover == null || cover.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择要上传的封面图片");
        if (cover.getSize() > MAX_COVER_BYTES)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "封面图片不能超过 5 MB");
        String contentType = cover.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("image/"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "封面仅支持 JPG、PNG、WebP 等图片格式");
    }

    private void deleteCoverAfterCommit(Playlist playlist) {
        if (!Boolean.TRUE.equals(playlist.getCoverIsCustom())) return;
        deleteObjectAfterCommit(playlist.getCoverUrl());
    }

    private void deleteObjectAfterCommit(String coverUrl) {
        if (coverUrl == null || coverUrl.isBlank()) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safeDelete(coverUrl);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { safeDelete(coverUrl); }
        });
    }

    private void safeDelete(String coverUrl) {
        try {
            minioService.deleteFile(coverUrl);
        } catch (RuntimeException e) {
            log.warn("Personal playlist cover cleanup failed after commit");
        }
    }

    private PlaylistDetailVO detail(Long id) {
        return playlistDetails.getPlaylistDetail(id, null).getData();
    }

    private Playlist metadata(Playlist playlist, PersonalPlaylistDTO input) {
        return playlist.setTitle(input.getTitle().trim()).setIntroduction(normalize(input.getIntroduction()))
                .setStyle(normalize(input.getStyle())).setIsPublic(Boolean.TRUE.equals(input.getIsPublic()));
    }

    private void requireSongs(List<Long> ids) {
        if (!ids.isEmpty() && songs.selectCount(new QueryWrapper<Song>().in("id", ids)) != ids.size())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "部分歌曲不存在，歌单未修改");
    }

    private List<Long> normalizeIds(List<Long> ids) {
        return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().sorted().toList();
    }

    private String normalize(String value) { return value == null ? "" : value.trim(); }

    private <T> void validate(T value) {
        if (value == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求内容不能为空");
        var violations = validator.validate(value);
        if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
    }

    private String fingerprint(PersonalPlaylistDTO input, List<Long> songIds) {
        try {
            byte[] content = json.writeValueAsString(List.of(input.getTitle().trim(), normalize(input.getIntroduction()),
                    normalize(input.getStyle()), Boolean.TRUE.equals(input.getIsPublic()), songIds)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to fingerprint playlist creation", e);
        }
    }
}
