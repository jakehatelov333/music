package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.mapper.AlbumMapper;
import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.mapper.SongMapper;
import cn.edu.seig.vibemusic.enumeration.ArtistStatusEnum;
import cn.edu.seig.vibemusic.enumeration.LyricStatusEnum;
import cn.edu.seig.vibemusic.model.dto.AlbumUploadCommitDTO;
import cn.edu.seig.vibemusic.model.dto.AudioMetadataDTO;
import cn.edu.seig.vibemusic.model.entity.Album;
import cn.edu.seig.vibemusic.model.entity.Artist;
import cn.edu.seig.vibemusic.model.entity.Song;
import cn.edu.seig.vibemusic.model.vo.AlbumUploadPendingArtistsVO;
import cn.edu.seig.vibemusic.model.vo.AlbumUploadPrepareVO;
import cn.edu.seig.vibemusic.model.vo.ArtistParsingVO;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.ArtistNameParser;
import cn.edu.seig.vibemusic.service.ArtistProvisioningService;
import cn.edu.seig.vibemusic.service.IAlbumUploadService;
import cn.edu.seig.vibemusic.service.IAudioMetadataService;
import cn.edu.seig.vibemusic.service.MinioService;
import cn.edu.seig.vibemusic.service.SongArtistService;
import lombok.extern.slf4j.Slf4j;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AlbumUploadServiceImpl implements IAlbumUploadService {
    private static final Map<String, List<StagedSong>> SESSIONS = new ConcurrentHashMap<>();
    private static final String REDIS_PREFIX = "album-upload:session:";
    private static final String STAGING_PREFIX = "staging/album-upload/";
    private static final long SESSION_TTL_HOURS = 48L;
    private static final Set<String> AUDIO_EXTENSIONS = Set.of("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus");
    private static final Set<String> COVER_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    @Autowired private AlbumMapper albumMapper;
    @Autowired private ArtistMapper artistMapper;
    @Autowired private SongMapper songMapper;
    @Autowired private MinioService minioService;
    @Autowired private IAudioMetadataService metadataService;
    @Autowired private SongArtistService songArtistService;
    @Autowired private ArtistNameParser artistNameParser;
    @Autowired private ArtistProvisioningService artistProvisioningService;

    /** 主艺人必须存在且处于启用状态（停用歌手不能再挂新专辑）。 */
    private boolean isActiveArtist(Long artistId) {
        Artist artist = artistMapper.selectById(artistId);
        return artist != null && ArtistStatusEnum.isActive(artist.getStatus());
    }
    @Autowired private StringRedisTemplate stringRedisTemplate;
    @Autowired private ObjectMapper objectMapper;

    @Override
    public Result<AlbumUploadPrepareVO> prepare(MultipartFile[] audio, MultipartFile cover) {
        if (audio == null || audio.length == 0) return Result.error("请至少选择一首音频");
        String id = UUID.randomUUID().toString();
        List<StagedSong> staged = new ArrayList<>();
        String album = null, artist = null, coverUrl = null;
        try {
            MultipartFile detectedCover = cover;
            if (detectedCover == null || detectedCover.isEmpty()) {
                detectedCover = Arrays.stream(audio)
                        .filter(file -> COVER_EXTENSIONS.contains(extensionOf(file.getOriginalFilename())))
                        .filter(file -> baseName(file.getOriginalFilename()).matches("(?i)cover(?:[._-].*)?"))
                        .findFirst().orElse(null);
            }
            StagedSong stagedCover = null;
            if (detectedCover != null && !detectedCover.isEmpty()) {
                String coverObject = "album-cover" + extensionSuffix(detectedCover.getOriginalFilename());
                coverUrl = minioService.uploadFile(detectedCover, "staging/album-upload/" + id, coverObject);
                stagedCover = new StagedSong("staging/album-upload/" + id + "/" + coverObject, coverUrl,
                        detectedCover.getOriginalFilename(), detectedCover.getSize(), new AudioMetadataDTO());
            }
            List<MultipartFile> audioFiles = new ArrayList<>();
            for (MultipartFile file : audio) {
                if (file == null || file.isEmpty()) continue;
                String extension = extensionOf(file.getOriginalFilename());
                if (AUDIO_EXTENSIONS.contains(extension)) {
                    audioFiles.add(file);
                }
            }
            audioFiles.sort(Comparator.comparingInt((MultipartFile file) -> trackNumberOf(file.getOriginalFilename()))
                    .thenComparing(file -> file.getOriginalFilename(), String.CASE_INSENSITIVE_ORDER));
            if (audioFiles.isEmpty()) {
                minioService.deleteObjectsByPrefix("staging/album-upload/" + id + "/");
                return Result.error("未找到可上传的音频文件");
            }
            for (int i = 0; i < audioFiles.size(); i++) {
                MultipartFile file = audioFiles.get(i);
                AudioMetadataDTO m = metadataService.parse(file);
                String object = "staging/album-upload/" + id + "/" + UUID.randomUUID() + "-" + safeName(file.getOriginalFilename());
                String url = minioService.uploadFile(file, "staging/album-upload/" + id, object.substring(object.lastIndexOf('/') + 1));
                StagedSong s = new StagedSong(object, url, file.getOriginalFilename(), file.getSize(), m);
                staged.add(s);
                if (!StringUtils.hasText(album) && StringUtils.hasText(m.getAlbum())) album = m.getAlbum();
                if (!StringUtils.hasText(artist) && StringUtils.hasText(m.getArtist())) artist = m.getArtist();
            }
            List<StagedSong> sessionEntries = new ArrayList<>(staged);
            if (stagedCover != null) sessionEntries.add(stagedCover);
            SESSIONS.put(id, sessionEntries);
            saveSession(id, sessionEntries);
            AlbumUploadPrepareVO vo = new AlbumUploadPrepareVO(); vo.setUploadId(id); vo.setAlbumName(album); vo.setArtistName(artist); vo.setCoverUrl(coverUrl); vo.setReleaseDate(firstReleaseDate(staged));
            List<AlbumUploadPrepareVO.Song> songs = new ArrayList<>();
            for (int i = 0; i < staged.size(); i++) {
                songs.add(buildSongVO(staged.get(i), i + 1, coverUrl));
            }
            vo.setSongs(songs); return Result.success(vo);
        } catch (Exception e) { minioService.deleteObjectsByPrefix("staging/album-upload/" + id + "/"); return Result.error("专辑解析失败：" + e.getMessage()); }
    }

    @Override @Transactional(rollbackFor = Exception.class)
    public Result<Object> commit(AlbumUploadCommitDTO dto) {
        if (dto == null || dto.getAlbum() == null || dto.getSongs() == null || dto.getSongs().isEmpty()) return Result.error("上传任务参数不完整");
        List<StagedSong> sessionSongs = getSession(dto.getUploadId());
        if (sessionSongs == null) return Result.error("上传任务已过期，请从草稿箱重新打开");
        StagedSong coverEntry = sessionSongs.stream().filter(this::isCoverEntry).findFirst().orElse(null);
        List<StagedSong> staged = sessionSongs.stream().filter(s -> !isCoverEntry(s)).collect(Collectors.toList());
        if (dto.getSongs().size() != staged.size()) return Result.error("歌曲列表与已上传文件数量不一致");
        AlbumUploadCommitDTO.AlbumInfo a = dto.getAlbum();
        a.setStatus("DRAFT");
        Long artistId = a.getArtistId();
        if (artistId == null && StringUtils.hasText(a.getArtistName())) { List<Artist> list = artistMapper.selectList(new QueryWrapper<Artist>().eq("name", a.getArtistName().trim()).eq("status", ArtistStatusEnum.ACTIVE.getStatus()).last("LIMIT 1")); if (!list.isEmpty()) artistId = list.get(0).getArtistId(); }
        if (!StringUtils.hasText(a.getAlbumName())) return Result.error("专辑名称不能为空");
        LocalDate releaseDate = a.getReleaseDate() != null ? a.getReleaseDate() : LocalDate.now();
        if (releaseDate.isBefore(LocalDate.of(1900, 1, 1)) || releaseDate.isAfter(LocalDate.now())) return Result.error("发行日期不能晚于今天");
        if (artistId == null || !isActiveArtist(artistId)) return Result.error("主艺人不能为空，请从歌手列表中选择已存在的歌手");
        for (StagedSong item : staged) {
            if (!minioService.objectExists(item.object)) return Result.error("文件尚未上传完成：" + item.fileName);
        }
        // ---- 歌手：先只读解析并决定是否需要管理员确认，此步不写任何数据、不转存任何文件 ----
        ArtistAssignment assignment = planArtists(dto, artistId);
        if (!assignment.ambiguousNames().isEmpty()) {
            return Result.error("以下歌手存在同名多条记录，请先到歌手管理确认后再上传："
                    + String.join("、", assignment.ambiguousNames()));
        }
        if (!assignment.missingNames().isEmpty() && !Boolean.TRUE.equals(dto.getAllowCreate())) {
            // 第一段：只回清单、不落库。管理员在弹框确认后带 allowCreate=true 重新提交。
            return Result.confirmationRequired(
                    "检测到 " + assignment.missingNames().size() + " 位歌手尚不存在，确认后将自动创建并继续上传",
                    pendingArtists(assignment, dto.getSongs().size()));
        }
        // 第二段：把缺失的名字真正建成歌手，再把名字换回 id。
        // 创建歌手与歌曲入库处在同一事务里，后续任一步失败都会一起回滚。
        List<List<Long>> songArtistIdsPerSong =
                assignment.bind(artistProvisioningService.createMissing(assignment.missingNames()));
        List<String> copiedObjects = new ArrayList<>();
        String coverUrl = a.getCoverUrl();
        Album album = albumMapper.selectOne(new QueryWrapper<Album>().eq("artist_id", artistId).eq("name", a.getAlbumName().trim()));
        // 封面在暂存目录，需转存到正式目录后再入库（提交成功会清理 staging）
        if (coverEntry != null && minioService.objectExists(coverEntry.object)) {
            String targetCoverName = "album-cover-" + UUID.randomUUID() + extensionSuffix(coverEntry.fileName);
            coverUrl = minioService.copyObject(coverEntry.object, "albumCovers", targetCoverName);
            copiedObjects.add("albumCovers/" + targetCoverName);
        }
        if (album == null) {
            album = new Album().setArtistId(artistId).setAlbumName(a.getAlbumName().trim()).setCoverUrl(coverUrl).setReleaseDate(a.getReleaseDate()).setDescription(a.getDescription()).setStatus(StringUtils.hasText(a.getStatus()) ? a.getStatus() : "DRAFT");
            albumMapper.insert(album);
        } else if (coverEntry != null) {
            // 专辑已存在时同样更新封面，避免重新上传同名专辑后封面依旧为空
            albumMapper.update(null, new UpdateWrapper<Album>().eq("id", album.getAlbumId()).set("cover_url", coverUrl));
            album.setCoverUrl(coverUrl);
        }
        try {
            for (int i = 0; i < dto.getSongs().size(); i++) {
                AlbumUploadCommitDTO.SongInfo x = dto.getSongs().get(i);
                StagedSong raw = staged.get(i);
                // 逐首歌的歌手已在上面统一解析（顺序即展示顺序，第一个为主艺人）。
                // 注意专辑的 artist_id 仍是专辑主艺人，与歌曲级歌手是两回事。
                List<Long> songArtistIds = songArtistIdsPerSong.get(i);
                if (songArtistIds.isEmpty()) {
                    songArtistIds = List.of(artistId);
                }
                Long songPrimaryArtistId = songArtistIds.get(0);

                String targetName = "songs/" + UUID.randomUUID() + "-" + safeName(raw.fileName);
                String audioUrl = minioService.copyObject(raw.object, "songs", targetName.substring(targetName.lastIndexOf('/') + 1));
                copiedObjects.add(targetName);
                String albumLyric = x.getLyrics();
                boolean hasAlbumLyric = StringUtils.hasText(albumLyric);
                Song song = new Song()
                        .setArtistId(songPrimaryArtistId)
                        .setAlbumId(album.getAlbumId())
                        .setAlbum(album.getAlbumName())
                        .setSongName(x.getSongName())
                        .setTrackNumber(x.getTrackNumber() == null ? i + 1 : x.getTrackNumber())
                        .setDiscNumber(x.getDiscNumber() == null ? 1 : x.getDiscNumber())
                        .setDuration(durationValue(raw.metadata.getDurationSeconds()))
                        .setCoverUrl(coverUrl)
                        .setAudioUrl(audioUrl)
                        .setReleaseTime(releaseDate)
                        .setLyric(hasAlbumLyric ? albumLyric : null)
                        .setLyricSource(hasAlbumLyric ? "MANUAL" : null)
                        .setLyricStatus(hasAlbumLyric ? LyricStatusEnum.MANUAL.getId() : LyricStatusEnum.MISSING.getId())
                        .setLyricUpdatedAt(hasAlbumLyric ? LocalDateTime.now() : null);
                songMapper.insert(song);
                // 关系表 + 展示串：本方法已由 @Transactional 覆盖，失败会随外层 catch 一起回滚。
                songArtistService.replaceSongArtists(song.getSongId(), songArtistIds);
            }
        } catch (Exception e) {
            for (String object : copiedObjects) { try { minioService.deleteObject(object); } catch (Exception ignored) {} }
            throw e;
        }
        // 清理暂存文件属于收尾动作：放到事务提交之后再执行，且失败只记日志、不回滚已入库的专辑；
        // 万一清理失败，由 AlbumUploadCleanupTask（每小时）与后台“清除草稿”兜底。
        cleanupStagingAfterCommit(dto.getUploadId());
        return Result.success(Map.of("albumId", album.getAlbumId(), "songCount", dto.getSongs().size()));
    }

    /**
     * 一次提交的歌手计划：逐首歌的有序歌手位 + 全专辑汇总的「待创建 / 同名歧义」名单。
     *
     * <p>做成不可变记录而不是就地改 DTO，是为了把「解析（只读）」与「创建（写库）」两段
     * 明确分开：解析阶段算出的清单可以直接回给管理员确认，确认后才走 {@link #bind}。
     */
    private record ArtistAssignment(List<List<ArtistProvisioningService.ArtistSlot>> slotsPerSong,
                                    List<String> missingNames,
                                    List<String> ambiguousNames) {

        /** 是否需要管理员确认（存在库里没有、可创建的歌手）。 */
        boolean requiresConfirmation() {
            return !missingNames.isEmpty();
        }

        /**
         * 把歌手位里的待创建名字换成真实 id。
         * 任一名字拿不到 id（创建失败）立即抛异常，由外层事务整体回滚，不留半成品。
         */
        List<List<Long>> bind(Map<String, Long> createdArtists) {
            List<List<Long>> result = new ArrayList<>(slotsPerSong.size());
            for (List<ArtistProvisioningService.ArtistSlot> slots : slotsPerSong) {
                List<Long> ids = new ArrayList<>(slots.size());
                for (ArtistProvisioningService.ArtistSlot slot : slots) {
                    Long id = slot.artistId() != null ? slot.artistId() : createdArtists.get(slot.artistName());
                    if (id == null) {
                        throw new IllegalStateException("歌手「" + slot.artistName() + "」创建失败，上传已中止");
                    }
                    ids.add(id);
                }
                result.add(SongArtistService.normalizeArtistIds(ids, null));
            }
            return result;
        }
    }

    /**
     * 只读地确定每首歌的歌手（<b>不写库、不转存文件</b>）。
     *
     * <p>优先级：显式 {@code artistIds} / 旧单值 {@code artistId} → 解析音频标签里的歌手串
     * （可能是「银临、Tacke竹桑」这种多歌手）→ 歌手字段全空时沿用专辑主艺人。
     *
     * <p><b>关键</b>：库里不存在的名字既不静默丢弃、也不擅自创建，而是汇总进
     * {@code missingNames} 交给管理员确认 —— 这是「解析结果与入库结果不一致」的根治点。
     */
    private ArtistAssignment planArtists(AlbumUploadCommitDTO dto, Long albumArtistId) {
        List<List<ArtistProvisioningService.ArtistSlot>> slotsPerSong = new ArrayList<>(dto.getSongs().size());
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        LinkedHashSet<String> ambiguous = new LinkedHashSet<>();
        for (AlbumUploadCommitDTO.SongInfo x : dto.getSongs()) {
            List<Long> explicitIds = SongArtistService.normalizeArtistIds(x.getArtistIds(), x.getArtistId());
            if (!explicitIds.isEmpty()) {
                slotsPerSong.add(explicitIds.stream()
                        .map(id -> new ArtistProvisioningService.ArtistSlot(id, null)).toList());
                continue;
            }
            if (StringUtils.hasText(x.getArtistName())) {
                ArtistProvisioningService.Resolution resolution =
                        artistProvisioningService.resolve(x.getArtistName());
                slotsPerSong.add(resolution.slots());
                missing.addAll(resolution.missingNames());
                ambiguous.addAll(resolution.ambiguousNames());
                continue;
            }
            slotsPerSong.add(List.of(new ArtistProvisioningService.ArtistSlot(albumArtistId, null)));
        }
        return new ArtistAssignment(List.copyOf(slotsPerSong), List.copyOf(missing), List.copyOf(ambiguous));
    }

    private static AlbumUploadPendingArtistsVO pendingArtists(ArtistAssignment assignment, int songCount) {
        AlbumUploadPendingArtistsVO vo = new AlbumUploadPendingArtistsVO();
        vo.setMissingArtists(assignment.missingNames());
        vo.setAmbiguousArtists(assignment.ambiguousNames());
        vo.setSongCount(songCount);
        return vo;
    }

    /** 事务提交后再清理暂存目录与会话；无事务时直接清理。异常一律吞掉并记日志，避免影响已成功的入库结果。 */
    private void cleanupStagingAfterCommit(String uploadId) {
        if (!StringUtils.hasText(uploadId)) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safeClear(uploadId);
                }
            });
        } else {
            safeClear(uploadId);
        }
    }

    private void safeClear(String uploadId) {
        try {
            clear(uploadId);
        } catch (Exception e) {
            log.warn("专辑上传暂存清理失败，uploadId={}，将由定时任务兜底回收", uploadId, e);
        }
    }
    @Override public Result<Object> clear(String id) { SESSIONS.remove(id); deleteRedisSession(id); minioService.deleteObjectsByPrefix("staging/album-upload/" + id + "/"); return Result.success(); }

    @Override
    public Result<Object> createSession(List<AlbumUploadFileRequest> files) {
        if (files == null || files.isEmpty()) return Result.error("请至少选择一个文件");
        String id = UUID.randomUUID().toString();
        List<StagedSong> entries = new ArrayList<>();
        for (AlbumUploadFileRequest file : files) {
            if (file == null || !StringUtils.hasText(file.fileName()) || file.size() < 0) continue;
            String object = "staging/album-upload/" + id + "/" + (file.cover() ? "album-cover" : UUID.randomUUID()) + "-" + safeName(file.fileName());
            entries.add(new StagedSong(object, null, file.fileName(), file.size(), new AudioMetadataDTO()));
        }
        if (entries.isEmpty()) return Result.error("没有有效文件");
        saveSession(id, entries);
        return Result.success(Map.of("uploadId", id, "expiresInHours", SESSION_TTL_HOURS));
    }

    @Override
    public Result<Object> signFile(String uploadId, String fileName, long size, String contentType, boolean cover) {
        List<StagedSong> entries = getSession(uploadId);
        if (entries == null) return Result.error("上传任务已过期");
        StagedSong target = entries.stream().filter(s -> s.fileName.equals(fileName) && s.size == size).findFirst().orElse(null);
        if (target == null) return Result.error("文件不属于当前上传任务");
        String url = minioService.presignedPutUrl(target.object, Duration.ofMinutes(30));
        return Result.success(Map.of("uploadId", uploadId, "fileName", fileName, "objectName", target.object, "url", url, "method", "PUT", "expiresInSeconds", 1800));
    }

    @Override
    public Result<Object> completeFile(String uploadId, String fileName, long size, String objectName) {
        List<StagedSong> entries = getSession(uploadId);
        if (entries == null) return Result.error("上传任务已过期");
        StagedSong target = entries.stream().filter(s -> s.fileName.equals(fileName) && s.object.equals(objectName)).findFirst().orElse(null);
        if (target == null || !minioService.objectExists(objectName)) return Result.error("文件尚未上传完成");
        saveSession(uploadId, entries);
        return Result.success(Map.of("fileName", fileName, "objectName", objectName, "uploaded", true));
    }

    @Override
    public Result<AlbumUploadPrepareVO> parseStaging(String uploadId) {
        List<StagedSong> entries = getSession(uploadId);
        if (entries == null) return Result.error("上传任务已过期");
        List<StagedSong> parsed = new ArrayList<>();
        List<StagedSong> coverEntries = new ArrayList<>();
        String album = null, artist = null; String coverUrl = null;
        for (StagedSong entry : entries) {
            if (!minioService.objectExists(entry.object)) continue;
            if (isCoverEntry(entry)) {
                // 浏览器直传的文件只有对象名、没有 URL，这里按对象名拼出可访问地址；
                // 同时保留该条目，提交时才能把封面转存到正式目录。
                coverUrl = minioService.publicUrl(entry.object);
                coverEntries.add(new StagedSong(entry.object, coverUrl, entry.fileName, entry.size, entry.metadata));
                continue;
            }
            String ext = extensionOf(entry.fileName);
            if (!AUDIO_EXTENSIONS.contains(ext)) continue;
            AudioMetadataDTO metadata;
            try (InputStream input = minioService.getObject(entry.object)) {
                metadata = metadataService.parse(input, entry.fileName, entry.size);
            } catch (Exception e) {
                metadata = new AudioMetadataDTO();
            }
            parsed.add(new StagedSong(entry.object, StringUtils.hasText(entry.url) ? entry.url : "", entry.fileName, entry.size, metadata));
            if (!StringUtils.hasText(album) && StringUtils.hasText(metadata.getAlbum())) album = metadata.getAlbum();
            if (!StringUtils.hasText(artist) && StringUtils.hasText(metadata.getArtist())) artist = metadata.getArtist();
        }
        parsed.sort(Comparator.comparingInt((StagedSong s) -> trackNumberOf(s.fileName)).thenComparing(s -> s.fileName, String.CASE_INSENSITIVE_ORDER));
        // 音频条目在前、封面条目在后：提交时按顺序取音频，封面单独转存
        List<StagedSong> toSave = new ArrayList<>(parsed);
        toSave.addAll(coverEntries);
        saveSession(uploadId, toSave);
        return Result.success(toPrepare(uploadId, parsed, album, artist, coverUrl));
    }

    /** 判断是否为专辑封面条目（直传流程按对象名 album-cover 命名，也兼容按文件名 cover.* 的旧命名）。 */
    private boolean isCoverEntry(StagedSong entry) {
        if (entry == null) return false;
        if (entry.object != null && entry.object.contains("/album-cover")) return true;
        String ext = extensionOf(entry.fileName);
        return COVER_EXTENSIONS.contains(ext) && baseName(entry.fileName).matches("(?i)cover(?:[._-].*)?");
    }

    @Override public Result<Object> session(String uploadId) { List<StagedSong> entries = getSession(uploadId); return entries == null ? Result.error("上传任务已过期") : Result.success(Map.of("uploadId", uploadId, "files", entries)); }

    @Override
    public int cleanupOrphanStaging() {
        List<String> objects = minioService.listObjectNames(STAGING_PREFIX);
        Set<String> uploadIds = new LinkedHashSet<>();
        for (String object : objects) {
            if (object == null || !object.startsWith(STAGING_PREFIX)) continue;
            String rest = object.substring(STAGING_PREFIX.length());
            int slash = rest.indexOf('/');
            if (slash > 0) uploadIds.add(rest.substring(0, slash));
        }
        int cleaned = 0;
        for (String uploadId : uploadIds) {
            try {
                // 会话以 Redis 为准：key 仍在说明草稿尚未结束，其暂存文件必须保留
                boolean sessionAlive = Boolean.TRUE.equals(stringRedisTemplate.hasKey(REDIS_PREFIX + uploadId))
                        || SESSIONS.containsKey(uploadId);
                if (sessionAlive) continue;
                minioService.deleteObjectsByPrefix(STAGING_PREFIX + uploadId + "/");
                cleaned++;
                log.info("已清理过期的专辑上传暂存文件，uploadId={}", uploadId);
            } catch (Exception e) {
                log.warn("清理专辑上传暂存文件失败，uploadId={}", uploadId, e);
            }
        }
        if (cleaned > 0) {
            log.info("专辑上传暂存清理完成，共清理 {} 个过期任务目录", cleaned);
        }
        return cleaned;
    }

    private AlbumUploadPrepareVO toPrepare(String id, List<StagedSong> staged, String album, String artist, String coverUrl) {
        AlbumUploadPrepareVO vo = new AlbumUploadPrepareVO(); vo.setUploadId(id); vo.setAlbumName(album); vo.setArtistName(artist); vo.setCoverUrl(coverUrl); vo.setReleaseDate(firstReleaseDate(staged));
        List<AlbumUploadPrepareVO.Song> songs = new ArrayList<>();
        for (int i = 0; i < staged.size(); i++) {
            songs.add(buildSongVO(staged.get(i), i + 1, coverUrl));
        }
        vo.setSongs(songs); return vo;
    }

    /**
     * 组装预览里的单首曲目。歌手的解析结论在这里一并算出（<b>只读，绝不建歌手</b>）：
     * 高置信度时给出可直接预填的 {@code artistIds}，其余情况把未匹配的名字原样交给管理员核对。
     *
     * @param fallbackTrack 文件名里读不到曲序时的兜底序号（从 1 开始）
     */
    private AlbumUploadPrepareVO.Song buildSongVO(StagedSong s, int fallbackTrack, String coverUrl) {
        AudioMetadataDTO m = s.metadata;
        AlbumUploadPrepareVO.Song x = new AlbumUploadPrepareVO.Song();
        x.setStagingKey(s.object);
        x.setFileName(s.fileName);
        x.setSongName(StringUtils.hasText(m.getTitle()) ? m.getTitle() : s.fileName);
        x.setArtistName(m.getArtist());
        x.setTrackNumber(m.getTrackNumber() == null ? trackNumberOf(s.fileName, fallbackTrack) : m.getTrackNumber());
        x.setDiscNumber(m.getDiscNumber() == null ? 1 : m.getDiscNumber());
        x.setDuration(m.getDurationSeconds());
        x.setDurationText(m.getDurationText());
        x.setFileSize(s.size);
        x.setCoverUrl(coverUrl);
        ArtistParsingVO parsing = artistNameParser.parse(m.getArtist());
        x.setArtistParsing(parsing);
        x.setArtistIds(confidentArtistIds(parsing));
        return x;
    }

    /**
     * 只有「完整匹配」或「每一段都唯一命中」才自动预填歌手，与单曲上传的判定规则保持一致；
     * 其余情况（含未匹配、同名歧义）返回 {@code null}，交给管理员人工确认，绝不替用户猜。
     */
    private static List<Long> confidentArtistIds(ArtistParsingVO parsing) {
        if (parsing == null || parsing.getCandidates() == null) {
            return null;
        }
        boolean confident = ArtistNameParser.STRATEGY_EXACT_MATCH.equals(parsing.getStrategy())
                || ArtistNameParser.STRATEGY_SPLIT_CONFIRMED.equals(parsing.getStrategy());
        if (!confident) {
            return null;
        }
        List<Long> ids = parsing.getCandidates().stream()
                .filter(ArtistParsingVO.Candidate::isMatched)
                .map(ArtistParsingVO.Candidate::getArtistId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return ids.isEmpty() ? null : ids;
    }

    private void saveSession(String id, List<StagedSong> entries) { try { stringRedisTemplate.opsForValue().set(REDIS_PREFIX+id, objectMapper.writeValueAsString(entries), SESSION_TTL_HOURS, java.util.concurrent.TimeUnit.HOURS); } catch(Exception e) { throw new IllegalStateException("上传状态保存失败",e); } }
    private List<StagedSong> getSession(String id) { if (!StringUtils.hasText(id)) return null; try { String json=stringRedisTemplate.opsForValue().get(REDIS_PREFIX+id); if (StringUtils.hasText(json)) return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, StagedSong.class)); } catch(Exception ignored) {} return SESSIONS.get(id); }
    private void deleteRedisSession(String id) { if (StringUtils.hasText(id)) stringRedisTemplate.delete(REDIS_PREFIX+id); }
    private String safeName(String name) { return StringUtils.hasText(name) ? name.replaceAll("[^\\w.\\-]", "_") : UUID.randomUUID().toString(); }
    private String extensionOf(String name) { if (!StringUtils.hasText(name)) return ""; int index = name.lastIndexOf('.'); return index >= 0 ? name.substring(index + 1).toLowerCase(Locale.ROOT) : ""; }
    private String baseName(String name) { if (!StringUtils.hasText(name)) return ""; String normalized = name.replace('\\', '/'); String file = normalized.substring(normalized.lastIndexOf('/') + 1); int index = file.lastIndexOf('.'); return index > 0 ? file.substring(0, index) : file; }
    private int trackNumberOf(String name) { return trackNumberOf(name, Integer.MAX_VALUE); }
    private int trackNumberOf(String name, int fallback) { if (!StringUtils.hasText(name)) return fallback; java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^\\s*(\\d{1,4})(?:[\\s._-]|$)").matcher(name); return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback; }
    private String extensionSuffix(String name) { String extension = extensionOf(name); return extension.isEmpty() ? "" : "." + extension; }

    /** 从音频元数据中取第一个有效年份作为专辑发行日期（如 2020-01-01），用于前端回填，避免发行日期为空导致入库失败。 */
    private String firstReleaseDate(List<StagedSong> staged) {
        if (staged != null) {
            for (StagedSong song : staged) {
                AudioMetadataDTO metadata = song.metadata;
                Integer year = metadata == null ? null : metadata.getYear();
                if (year != null && year > 0) {
                    return LocalDate.of(year, 1, 1).toString();
                }
            }
        }
        return null;
    }

    /** 时长按秒保留两位小数写入 tb_song.duration（varchar(10)），与单曲上传流程保持一致。 */
    private String durationValue(Double seconds) {
        if (seconds == null || seconds <= 0) {
            return null;
        }
        return String.format(Locale.ROOT, "%.2f", seconds);
    }

    private record StagedSong(String object, String url, String fileName, long size, AudioMetadataDTO metadata) {}
}
