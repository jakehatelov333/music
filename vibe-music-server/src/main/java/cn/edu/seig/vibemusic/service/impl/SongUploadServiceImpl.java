package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.mapper.AlbumMapper;
import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.mapper.SongMapper;
import cn.edu.seig.vibemusic.enumeration.ArtistStatusEnum;
import cn.edu.seig.vibemusic.enumeration.LyricStatusEnum;
import cn.edu.seig.vibemusic.model.dto.AudioMetadataDTO;
import cn.edu.seig.vibemusic.model.dto.SongUploadCommitDTO;
import cn.edu.seig.vibemusic.model.dto.SongUploadSession;
import cn.edu.seig.vibemusic.model.entity.Album;
import cn.edu.seig.vibemusic.model.entity.Artist;
import cn.edu.seig.vibemusic.model.entity.Song;
import cn.edu.seig.vibemusic.model.vo.ArtistNameVO;
import cn.edu.seig.vibemusic.model.vo.SongUploadCommitVO;
import cn.edu.seig.vibemusic.model.vo.SongUploadFilesVO;
import cn.edu.seig.vibemusic.model.vo.SongUploadMetadataVO;
import cn.edu.seig.vibemusic.model.vo.SongUploadPrepareVO;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.ArtistNameParser;
import cn.edu.seig.vibemusic.service.ArtistProvisioningService;
import cn.edu.seig.vibemusic.service.IAudioMetadataService;
import cn.edu.seig.vibemusic.service.ISongUploadService;
import cn.edu.seig.vibemusic.service.MinioService;
import cn.edu.seig.vibemusic.service.SongArtistService;
import cn.edu.seig.vibemusic.service.cover.CoverDownloader;
import cn.edu.seig.vibemusic.service.cover.QqCoverUrl;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 临时上传、资源补充、最终入库与 staging 清理。
 */
@Slf4j
@Service
public class SongUploadServiceImpl implements ISongUploadService {

    private static final String SESSION_KEY_PREFIX = "song-upload:session:";
    private static final String SESSION_INDEX_KEY = "song-upload:session-index";
    private static final String COMMIT_LOCK_PREFIX = "song-upload:commit-lock:";
    private static final String STAGING_ROOT = "staging/song-upload";
    /**
     * 未提交上传的存活时长（小时）。<b>暂存文件与 Redis 会话必须用同一个值</b>。
     *
     * <p>历史上这里是两个常量：暂存文件 24 小时、会话 48 小时。后果是用户在第 25 小时
     * 打开页面，界面仍然显示「上传中」（会话还在），但 staging 文件已经被定时任务删了，
     * 点提交只会得到「临时音频文件不存在或已过期」。这种「界面说还在、实际已没了」
     * 比两边都过期更糟：用户会以为是自己操作失误。
     *
     * <p>取 12 小时：一次上传从选文件到提交，正常在几分钟内完成；12 小时足够覆盖
     * 「传完先放着、下班前再来提交」的场景，同时把孤儿文件的平均驻留时间压到原来的一半。
     */
    private static final long UPLOAD_TTL_HOURS = 12L;
    private static final long UPLOAD_TTL_MILLIS = TimeUnit.HOURS.toMillis(UPLOAD_TTL_HOURS);
    private static final Set<String> AUDIO_EXTENSIONS = Set.of("mp3", "flac", "wav");
    private static final Set<String> COVER_EXTENSIONS = Set.of("jpg", "jpeg", "png");
    private static final Set<String> LYRICS_EXTENSIONS = Set.of("lrc", "txt");

    /** 封面来源：从音频文件里提取的内嵌图 */
    private static final String COVER_SOURCE_EMBEDDED = "EMBEDDED";
    /** 封面来源：管理员手动上传 */
    private static final String COVER_SOURCE_UPLOADED = "UPLOADED";
    /** 封面来源：按专辑从 QQ 音乐搜索下载 */
    private static final String COVER_SOURCE_SEARCHED = "SEARCHED";

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ArtistMapper artistMapper;
    @Autowired
    private AlbumMapper albumMapper;
    @Autowired
    private SongMapper songMapper;
    @Autowired
    private MinioService minioService;
    @Autowired
    private IAudioMetadataService audioMetadataService;
    @Autowired
    private SongArtistService songArtistService;
    @Autowired
    private ArtistNameParser artistNameParser;
    @Autowired
    private ArtistProvisioningService artistProvisioningService;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private CoverDownloader coverDownloader;

    @Override
    public Result<SongUploadPrepareVO> prepare(MultipartFile audio) {
        log.info("收到歌曲上传准备请求: file={}, size={}", audio == null ? null : audio.getOriginalFilename(), audio == null ? 0 : audio.getSize());
        if (audio == null || audio.isEmpty()) {
            return Result.error("请选择音频文件");
        }

        String audioExtension = extensionOf(audio.getOriginalFilename());
        if (!AUDIO_EXTENSIONS.contains(audioExtension)) {
            return Result.error("仅支持 MP3、FLAC、WAV 格式的音频文件");
        }

        String uploadId = UUID.randomUUID().toString();
        log.info("歌曲上传开始解析: file={}, size={}, uploadId={}", audio.getOriginalFilename(), audio.getSize(), uploadId);
        String stagingFolder = stagingFolder(uploadId);
        String audioObjectName = stagingFolder + "/audio." + audioExtension;
        String audioUrl;
        try {
            audioUrl = minioService.uploadFile(audio, stagingFolder, "audio." + audioExtension);
        } catch (RuntimeException e) {
            log.error("歌曲上传 staging 写入失败，file={}", audio.getOriginalFilename(), e);
            return Result.error(e.getMessage());
        }

        AudioMetadataDTO metadata = audioMetadataService.parse(audio);
        String coverObjectName = null;
        String coverUrl = null;
        String coverSource = null;
        String coverWarning = null;
        if (metadata.isHasEmbeddedCover() && metadata.getEmbeddedCover() != null) {
            String coverExtension = coverExtension(metadata.getEmbeddedCoverMimeType());
            String embeddedCoverName = "cover-embedded." + coverExtension;
            try {
                coverUrl = minioService.uploadBytes(
                        metadata.getEmbeddedCover(),
                        stagingFolder,
                        embeddedCoverName,
                        StringUtils.hasText(metadata.getEmbeddedCoverMimeType())
                                ? metadata.getEmbeddedCoverMimeType()
                                : "image/" + ("jpg".equals(coverExtension) ? "jpeg" : coverExtension)
                );
                coverObjectName = stagingFolder + "/" + embeddedCoverName;
                coverSource = COVER_SOURCE_EMBEDDED;
            } catch (RuntimeException e) {
                coverWarning = "内嵌封面提取失败，请手动上传封面。";
                log.warn("内嵌封面上传失败，uploadId={}", uploadId, e);
            }
        }

        long now = System.currentTimeMillis();
        SongUploadSession session = SongUploadSession.builder()
                .uploadId(uploadId)
                .createdAt(now)
                .lastActivityAt(now)
                .audioObjectName(audioObjectName)
                .audioUrl(audioUrl)
                .audioOriginalName(audio.getOriginalFilename())
                .audioContentType(audio.getContentType())
                .audioSize(audio.getSize())
                .audioFormat(metadata.getAudioFormat())
                .durationSeconds(metadata.getDurationSeconds())
                .durationText(metadata.getDurationText())
                .durationParsed(metadata.isDurationParsed())
                .title(metadata.getTitle())
                .artist(metadata.getArtist())
                .album(metadata.getAlbum())
                .year(metadata.getYear())
                .metadataAvailable(metadata.isMetadataAvailable())
                .hasEmbeddedCover(metadata.isHasEmbeddedCover())
                .coverObjectName(coverObjectName)
                .coverUrl(coverUrl)
                .coverSource(coverSource)
                .trackNumber(metadata.getTrackNumber())
                .discNumber(metadata.getDiscNumber())
                .build();

        try {
            saveSession(session);
        } catch (RuntimeException e) {
            minioService.deleteObjectsByPrefix(stagingFolder);
            return Result.error("保存临时上传状态失败，请重试");
        }

        String warning = buildWarning(session);
        if (StringUtils.hasText(coverWarning)) {
            warning = StringUtils.hasText(warning) ? warning + " " + coverWarning : coverWarning;
        }
        log.info(
                "歌曲上传解析完成: uploadId={}, durationParsed={}, duration={}, title={}, artist={}",
                uploadId,
                session.isDurationParsed(),
                session.getDurationSeconds(),
                session.getTitle(),
                session.getArtist()
        );
        return Result.success("音频上传并解析成功", buildPrepareVO(session, warning));
    }

    @Override
    public Result<SongUploadPrepareVO> updateAssets(String uploadId, MultipartFile cover, MultipartFile lyrics) {
        if (!isValidUploadId(uploadId)) {
            return Result.error("上传任务不存在或参数不合法");
        }
        SongUploadSession session = getSession(uploadId);
        if (session == null) {
            return Result.error("上传任务已过期，请重新上传音频");
        }
        if ((cover == null || cover.isEmpty()) && (lyrics == null || lyrics.isEmpty())) {
            return Result.error("请选择需要补充的封面或歌词文件");
        }

        String obsoleteCoverObjectName = null;
        String obsoleteLyricsObjectName = null;

        if (cover != null && !cover.isEmpty()) {
            String coverExtension = extensionOf(cover.getOriginalFilename());
            if (!COVER_EXTENSIONS.contains(coverExtension)) {
                return Result.error("封面仅支持 JPG、JPEG、PNG 格式");
            }
            String objectName = "cover-uploaded-" + UUID.randomUUID() + "." + coverExtension;
            String oldCoverObjectName = session.getCoverObjectName();
            try {
                String newCoverUrl = minioService.uploadFile(cover, stagingFolder(uploadId), objectName);
                session.setCoverObjectName(stagingFolder(uploadId) + "/" + objectName);
                session.setCoverUrl(newCoverUrl);
                session.setCoverSource(COVER_SOURCE_UPLOADED);
                if (StringUtils.hasText(oldCoverObjectName) && !oldCoverObjectName.equals(session.getCoverObjectName())) {
                    obsoleteCoverObjectName = oldCoverObjectName;
                }
            } catch (RuntimeException e) {
                log.error("歌曲封面上传失败，uploadId={}", uploadId, e);
                return Result.error("封面上传失败，请重试");
            }
        }

        if (lyrics != null && !lyrics.isEmpty()) {
            String lyricsExtension = extensionOf(lyrics.getOriginalFilename());
            if (!LYRICS_EXTENSIONS.contains(lyricsExtension)) {
                return Result.error("歌词仅支持 LRC、TXT 格式");
            }
            try {
                byte[] lyricsBytes = lyrics.getBytes();
                String lyricsContent = decodeText(lyricsBytes);
                String lyricsObjectName = "lyrics." + lyricsExtension;
                String lyricsUrl = minioService.uploadBytes(
                        lyricsBytes,
                        stagingFolder(uploadId),
                        lyricsObjectName,
                        "text/plain;charset=UTF-8"
                );
                String oldLyricsObjectName = session.getLyricsObjectName();
                session.setLyricsContent(lyricsContent);
                session.setLyricsObjectName(stagingFolder(uploadId) + "/" + lyricsObjectName);
                session.setLyricsUrl(lyricsUrl);
                if (StringUtils.hasText(oldLyricsObjectName) && !oldLyricsObjectName.equals(session.getLyricsObjectName())) {
                    obsoleteLyricsObjectName = oldLyricsObjectName;
                }
            } catch (Exception e) {
                log.error("歌词上传失败，uploadId={}", uploadId, e);
                return Result.error("歌词上传失败，请确认文件编码后重试");
            }
        }

        try {
            saveSession(session);
        } catch (RuntimeException e) {
            log.error("保存上传资源状态失败，uploadId={}", uploadId, e);
            return Result.error("保存上传状态失败，请重试");
        }
        safeDeleteObject(obsoleteCoverObjectName);
        safeDeleteObject(obsoleteLyricsObjectName);
        return Result.success("上传资源更新成功", buildPrepareVO(session, buildWarning(session)));
    }



    @Override
    @CacheEvict(cacheNames = {"songCache", "artistCache"}, allEntries = true)
    public Result<SongUploadCommitVO> commit(SongUploadCommitDTO dto) {
        if (dto == null || !isValidUploadId(dto.getUploadId())) {
            return Result.error("上传任务不存在或参数不合法");
        }
        SongUploadSession session = getSession(dto.getUploadId());
        if (session == null) {
            return Result.error("上传任务已过期，请重新上传音频");
        }
        if (!StringUtils.hasText(dto.getSongName())) {
            return Result.error("歌曲名不能为空");
        }

        LocalDate releaseDate = dto.getReleaseDate();
        if (releaseDate == null) {
            if (dto.getYear() == null) {
                return Result.error("请填写发行日期");
            }
            releaseDate = LocalDate.of(dto.getYear(), 1, 1);
        }
        if (releaseDate.isBefore(LocalDate.of(1900, 1, 1)) || releaseDate.isAfter(LocalDate.now())) {
            return Result.error("发行日期不能晚于今天");
        }

        String lockKey = COMMIT_LOCK_PREFIX + dto.getUploadId();
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", 5, TimeUnit.MINUTES);
        if (!Boolean.TRUE.equals(locked)) {
            return Result.error("该上传任务正在保存，请勿重复提交");
        }

        String finalAudioUrl = null;
        String finalAudioObjectName = null;
        String finalCoverUrl = null;
        String finalCoverObjectName = null;
        // 提交成功的分界线。一旦跨过它，正式封面/音频就已经被 tb_song 行引用，
        // 后续任何异常都<b>不得</b>再删除这两个对象 —— 否则会留下「记录在、文件没了」的静默损坏。
        boolean committed = false;
        try {
            // 歌手：artistIds 优先（顺序即展示顺序）；否则回退到旧的「按名字解析/创建单个歌手」。
            List<Long> requestedArtistIds = SongArtistService.normalizeArtistIds(
                    dto.getArtistIds(), dto.getArtistId());
            boolean artistCreated = false;
            List<Long> finalArtistIds;
            if (!requestedArtistIds.isEmpty()) {
                finalArtistIds = requestedArtistIds;
            } else {
                Artist resolved = resolveArtist(dto);
                Long resolvedId;
                if (resolved != null) {
                    resolvedId = resolved.getArtistId();
                } else {
                    // 含明确分隔符却没给出确认后的歌手列表 → 拒绝自动新建「A/B」这类伪歌手记录，
                    // 让管理员回到上传预览走人工确认（对应 docs §6 的判定优先级）。
                    if (artistNameParser.looksSplittable(dto.getArtistName())) {
                        return Result.error("歌手名「" + dto.getArtistName()
                                + "」疑似多位歌手合作，请先在上传预览中确认歌手列表");
                    }
                    // 创建歌手统一走 ArtistProvisioningService（与专辑上传共用同一实现）。
                    resolvedId = artistProvisioningService.resolveOrCreateId(dto.getArtistName());
                    artistCreated = true;
                }
                finalArtistIds = List.of(resolvedId);
            }
            Long primaryArtistId = finalArtistIds.get(0);
            if (!minioService.objectExists(session.getAudioObjectName())) {
                return Result.error("临时音频文件不存在或已过期，请重新上传");
            }

            String audioExtension = extensionFromObjectName(session.getAudioObjectName(), "mp3");
            String finalAudioName = UUID.randomUUID() + "." + audioExtension;
            finalAudioObjectName = "songs/" + finalAudioName;
            finalAudioUrl = minioService.copyObject(
                    session.getAudioObjectName(),
                    "songs",
                    finalAudioName
            );

            if (StringUtils.hasText(session.getCoverObjectName())) {
                if (!minioService.objectExists(session.getCoverObjectName())) {
                    throw new IllegalStateException("临时封面文件不存在或已过期");
                }
                String coverExtension = extensionFromObjectName(session.getCoverObjectName(), "jpg");
                String finalCoverName = UUID.randomUUID() + "." + coverExtension;
                finalCoverObjectName = "songCovers/" + finalCoverName;
                finalCoverUrl = minioService.copyObject(
                        session.getCoverObjectName(),
                        "songCovers",
                        finalCoverName
                );
            }

            Long albumId = resolveAlbumId(
                    primaryArtistId,
                    dto.getAlbum(),
                    dto.getAlbumId(),
                    releaseDate,
                    finalCoverUrl,
                    transactionTemplate()
            );
            Integer trackNumber = dto.getTrackNumber();
            if (trackNumber == null && albumId != null) {
                trackNumber = albumMapper.getNextTrackNumber(albumId);
            }
            if (trackNumber == null || trackNumber < 1) {
                trackNumber = 1;
            }

            String lyricText = dto.getLyrics() != null ? dto.getLyrics() : session.getLyricsContent();
            boolean hasLyric = StringUtils.hasText(lyricText);

            Song song = new Song()
                    .setArtistId(primaryArtistId)
                    .setAlbumId(albumId)
                    .setSongName(dto.getSongName().trim())
                    .setAlbum(trimToNull(dto.getAlbum()))
                    .setDiscNumber(dto.getDiscNumber() == null ? 1 : dto.getDiscNumber())
                    .setTrackNumber(trackNumber)
                    .setLyric(hasLyric ? lyricText : null)
                    .setLyricSource(hasLyric ? "MANUAL" : null)
                    .setLyricStatus(hasLyric ? LyricStatusEnum.MANUAL.getId() : LyricStatusEnum.MISSING.getId())
                    .setLyricUpdatedAt(hasLyric ? LocalDateTime.now() : null)
                    .setDuration(session.isDurationParsed() ? durationValue(session.getDurationSeconds()) : null)
                    .setStyle(trimToNull(dto.getStyle()))
                    .setCoverUrl(finalCoverUrl)
                    .setAudioUrl(finalAudioUrl)
                    .setReleaseTime(releaseDate);

            // 歌曲行与歌手关系必须同生共死：用一个独立的小事务包住这两步，失败整块回滚，
            // 不会留下「有歌曲却没有任何歌手关系」的孤儿行。
            // 这里用 TransactionTemplate 而不是给 commit 加 @Transactional —— 后者在外层
            // catch 吞掉异常后事务已被标记 rollback-only，提交时反而抛 UnexpectedRollbackException。
            TransactionTemplate songTx = transactionTemplate();
            songTx.executeWithoutResult(status -> {
                if (songMapper.insert(song) != 1) {
                    throw new IllegalStateException("歌曲信息保存失败");
                }
                songArtistService.replaceSongArtists(song.getSongId(), finalArtistIds);
            });
            committed = true;

            cleanupSession(dto.getUploadId());
            return Result.success("歌曲上传成功", buildCommitVO(song, primaryArtistId, artistCreated));
        } catch (Exception e) {
            if (committed) {
                // 已提交：正式封面/音频已被歌曲行引用，绝不能回收。
                // 组装响应失败不代表入库失败，重新读取已提交结果返回即可（幂等重放）。
                log.error("歌曲已入库，但组装响应失败，改为重读已提交结果，uploadId={}", dto.getUploadId(), e);
                Result<SongUploadCommitVO> recovered = recoverCommittedResult(dto);
                if (recovered != null) {
                    return recovered;
                }
                // 连重读都失败：明确告诉调用方「已保存」而不是「请重试」，
                // 避免管理员重复上传导致重复建歌。
                return Result.error("歌曲已保存成功，但返回结果读取失败，请稍后在曲库中确认，勿重复提交");
            }
            safeDeleteObject(finalCoverObjectName);
            safeDeleteObject(finalAudioObjectName);
            log.error("歌曲正式保存失败，uploadId={}", dto.getUploadId(), e);
            return Result.error(StringUtils.hasText(e.getMessage()) ? e.getMessage() : "歌曲保存失败，请重试");
        } finally {
            stringRedisTemplate.delete(lockKey);
        }
    }

    @Override
    public Result<SongUploadPrepareVO> updateCoverFromAlbum(String uploadId, String albumMid, Integer size) {
        if (!isValidUploadId(uploadId)) {
            return Result.error("上传任务不存在或参数不合法");
        }
        SongUploadSession session = getSession(uploadId);
        if (session == null) {
            return Result.error("上传任务已过期，请重新上传音频");
        }
        // 只接受 albumMid，不接受 URL：否则这个端点就成了「让服务端抓任意地址」的入口
        if (!QqCoverUrl.isValidAlbumMid(albumMid)) {
            return Result.error("专辑标识不合法");
        }

        int normalizedSize = QqCoverUrl.normalizeSize(size);
        String coverUrl = QqCoverUrl.of(albumMid, normalizedSize);
        CoverDownloader.Result downloaded = coverDownloader.download(coverUrl);
        if (!downloaded.success()) {
            return Result.error(downloaded.error());
        }

        String oldCoverObjectName = session.getCoverObjectName();
        String objectName = "cover-searched-" + UUID.randomUUID() + "." + coverExtension(downloaded.contentType());
        String newCoverUrl;
        try {
            newCoverUrl = minioService.uploadBytes(
                    downloaded.bytes(),
                    stagingFolder(uploadId),
                    objectName,
                    downloaded.contentType()
            );
        } catch (RuntimeException e) {
            log.error("封面写入 staging 失败，uploadId={}", uploadId, e);
            return Result.error("封面保存失败，请重试");
        }

        session.setCoverObjectName(stagingFolder(uploadId) + "/" + objectName);
        session.setCoverUrl(newCoverUrl);
        session.setCoverSource(COVER_SOURCE_SEARCHED);

        try {
            saveSession(session);
        } catch (RuntimeException e) {
            // 会话没保存成功，刚写入的这张图就成了孤儿：立刻回收，避免留下无主文件
            safeDeleteObject(stagingFolder(uploadId) + "/" + objectName);
            log.error("保存上传资源状态失败，uploadId={}", uploadId, e);
            return Result.error("保存上传状态失败，请重试");
        }
        safeDeleteObject(oldCoverObjectName);
        return Result.success("封面已应用", buildPrepareVO(session, buildWarning(session)));
    }

    @Override
    public Result clear(String uploadId) {
        if (!isValidUploadId(uploadId)) {
            return Result.error("上传任务不存在或参数不合法");
        }
        SongUploadSession session = getSession(uploadId);
        if (session == null) {
            minioService.deleteObjectsByPrefix(stagingFolder(uploadId));
            return Result.success("临时文件已清理");
        }
        cleanupSession(uploadId);
        return Result.success("临时文件已清空");
    }

    @Override
    public void cleanupExpired() {
        long cutoff = System.currentTimeMillis() - UPLOAD_TTL_MILLIS;
        Set<String> expiredUploadIds = stringRedisTemplate.opsForZSet()
                .rangeByScore(SESSION_INDEX_KEY, 0, cutoff);
        if (expiredUploadIds == null || expiredUploadIds.isEmpty()) {
            return;
        }
        for (String uploadId : expiredUploadIds) {
            try {
                minioService.deleteObjectsByPrefix(stagingFolder(uploadId));
                stringRedisTemplate.delete(sessionKey(uploadId));
                stringRedisTemplate.opsForZSet().remove(SESSION_INDEX_KEY, uploadId);
                log.info("已清理过期歌曲上传任务，uploadId={}", uploadId);
            } catch (Exception e) {
                log.warn("清理过期歌曲上传任务失败，uploadId={}", uploadId, e);
            }
        }
    }

    private Artist resolveArtist(SongUploadCommitDTO dto) {
        if (dto.getArtistId() != null) {
            Artist artist = artistMapper.selectById(dto.getArtistId());
            // 停用（软删除）的歌手不能再被新歌曲引用
            if (artist != null && ArtistStatusEnum.isActive(artist.getStatus())) {
                return artist;
            }
        }
        if (!StringUtils.hasText(dto.getArtistName())) {
            return null;
        }
        List<Artist> artists = artistMapper.selectList(
                new QueryWrapper<Artist>()
                        .eq("name", dto.getArtistName().trim())
                        .eq("status", ArtistStatusEnum.ACTIVE.getStatus())
                        .last("LIMIT 1")
        );
        return artists.isEmpty() ? null : artists.get(0);
    }

    /**
     * 解析或创建专辑。<b>必须在外层事务内调用</b>。
     *
     * <p>历史上这里是裸调用：歌曲提交失败时专辑的新建/更新已经写进去了，
     * 回滚不了，于是库里会留下「没有歌的空专辑」。把事务对象显式传进来，
     * 让「建专辑 + 建歌曲 + 建歌手关系」三者同生共死。
     */
    private Long resolveAlbumId(Long artistId, String albumName, Long albumId,
                                LocalDate releaseDate, String coverUrl,
                                TransactionTemplate transactionTemplate) {
        if (albumId != null) {
            return albumId;
        }
        if (artistId == null || !StringUtils.hasText(albumName)) {
            return null;
        }

        final String normalizedName = albumName.trim();
        return transactionTemplate.execute(status -> {
            Album album = albumMapper.selectOne(new QueryWrapper<Album>()
                    .eq("artist_id", artistId)
                    .eq("name", normalizedName)
                    .last("LIMIT 1"));
            if (album == null) {
                album = new Album()
                        .setArtistId(artistId)
                        .setAlbumName(normalizedName)
                        .setReleaseDate(releaseDate)
                        .setCoverUrl(coverUrl);
                albumMapper.insert(album);
                return album.getAlbumId();
            }

            boolean changed = false;
            if (album.getCoverUrl() == null && coverUrl != null) {
                album.setCoverUrl(coverUrl);
                changed = true;
            }
            if (album.getReleaseDate() == null && releaseDate != null) {
                album.setReleaseDate(releaseDate);
                changed = true;
            }
            if (changed) {
                albumMapper.updateById(album);
            }
            return album.getAlbumId();
        });
    }

    /**
     * 提交成功、但组装响应失败后的兜底：按歌曲名 + 主歌手重读刚写入的记录。
     *
     * <p>只重读<b>不写</b>，读不到就返回 {@code null} 交给调用方降级处理，
     * 绝不会为了「修好响应」而回删已经入库的媒体对象。
     */
    private Result<SongUploadCommitVO> recoverCommittedResult(SongUploadCommitDTO dto) {
        try {
            Song saved = songMapper.selectOne(new QueryWrapper<Song>()
                    .eq("song_name", dto.getSongName() == null ? null : dto.getSongName().trim())
                    .eq("artist_id", dto.getArtistId())
                    .orderByDesc("song_id")
                    .last("LIMIT 1"));
            if (saved == null) {
                return null;
            }
            return Result.success("歌曲上传成功",
                    buildCommitVO(saved, saved.getArtistId(), false));
        } catch (Exception e) {
            log.error("重读已提交歌曲失败，uploadId={}", dto.getUploadId(), e);
            return null;
        }
    }

    private SongUploadPrepareVO buildPrepareVO(SongUploadSession session, String warning) {
        SongUploadMetadataVO metadata = new SongUploadMetadataVO();
        metadata.setTitle(session.getTitle());
        metadata.setArtist(session.getArtist());
        metadata.setAlbum(session.getAlbum());
        metadata.setYear(session.getYear());
        metadata.setDuration(session.getDurationSeconds());
        metadata.setDurationText(session.getDurationText());
        metadata.setDurationParsed(session.isDurationParsed());
        metadata.setMetadataAvailable(session.isMetadataAvailable());
        metadata.setHasEmbeddedCover(session.isHasEmbeddedCover());
        metadata.setCoverUrl(session.getCoverUrl());
        metadata.setCoverSource(session.getCoverSource());
        metadata.setAudioFormat(session.getAudioFormat());
        metadata.setFileSize(session.getAudioSize());

        SongUploadFilesVO files = new SongUploadFilesVO();
        files.setAudioUrl(session.getAudioUrl());
        files.setCoverUrl(session.getCoverUrl());
        files.setLyricsUrl(session.getLyricsUrl());
        files.setLyricsContent(session.getLyricsContent());

        SongUploadPrepareVO vo = new SongUploadPrepareVO();
        vo.setUploadId(session.getUploadId());
        vo.setMetadata(metadata);
        vo.setFiles(files);
        vo.setWarning(warning);
        Artist matchedArtist = matchArtist(session.getArtist());
        if (matchedArtist != null) {
            vo.setArtistMatched(true);
            vo.setMatchedArtistId(matchedArtist.getArtistId());
            vo.setMatchedArtistName(matchedArtist.getArtistName());
        }
        // 解析歌手字段：是否疑似合作歌手、能否自动拆分、哪些名称需要人工确认。
        // 解析全程只读，不会创建任何歌手记录。
        vo.setArtistParsing(artistNameParser.parse(session.getArtist()));
        return vo;
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(transactionManager);
    }

    /**
     * 组装提交结果。歌手展示串与结构化列表<b>都从关系表派生</b>（而不是直接用传入的
     * {@code Artist} 实体），保证 {@code artistName} 与 {@code artists} 永远一致。
     */
    private SongUploadCommitVO buildCommitVO(Song song, Long primaryArtistId, boolean artistCreated) {
        SongUploadCommitVO vo = new SongUploadCommitVO();
        vo.setSongId(song.getSongId());
        vo.setSongName(song.getSongName());
        vo.setArtistId(primaryArtistId);
        vo.setArtistCreated(artistCreated);
        vo.setAlbum(song.getAlbum());
        vo.setDuration(song.getDuration());
        vo.setCoverUrl(song.getCoverUrl());
        vo.setAudioUrl(song.getAudioUrl());
        vo.setReleaseTime(song.getReleaseTime());
        songArtistService.attachArtists(vo);
        if (vo.getArtists() != null && !vo.getArtists().isEmpty()) {
            vo.setArtistId(vo.getArtists().get(0).getArtistId());
            vo.setArtistName(SongArtistService.renderArtistNames(
                    vo.getArtists().stream().map(ArtistNameVO::getArtistName).toList()));
        }
        return vo;
    }

    private String buildWarning(SongUploadSession session) {
        StringBuilder warning = new StringBuilder();
        if (!session.isDurationParsed()) {
            warning.append("未能从音频文件中解析时长，请确认该文件可正常播放。");
        }
        if (!session.isMetadataAvailable()
                || !StringUtils.hasText(session.getTitle())
                || !StringUtils.hasText(session.getArtist())
                || !StringUtils.hasText(session.getAlbum())
                || session.getYear() == null) {
            if (warning.length() > 0) {
                warning.append(' ');
            }
            warning.append("部分歌曲信息无法从音频文件中读取，已尝试使用文件名推断，请手动核对。");
        }
        return warning.toString();
    }

    private Artist matchArtist(String artistName) {
        if (!StringUtils.hasText(artistName)) {
            return null;
        }
        // 只匹配启用中的歌手：停用记录不该被当作「已匹配」而阻止人工确认。
        List<Artist> artists = artistMapper.selectList(
                new QueryWrapper<Artist>()
                        .eq("name", artistName.trim())
                        .eq("status", ArtistStatusEnum.ACTIVE.getStatus())
                        .last("LIMIT 1")
        );
        return artists.isEmpty() ? null : artists.get(0);
    }

    private void saveSession(SongUploadSession session) {
        try {
            // 每次保存都刷新「最后活动时间」。清理判据必须用它而不是 createdAt：
            // 否则用户在第 11 小时还在编辑，第 12 小时文件仍会被扫掉，
            // 而 Redis 键因为每次 save 都重设 TTL 才刚续期 —— 又是「界面说还在、文件已没」。
            long now = System.currentTimeMillis();
            session.setLastActivityAt(now);
            String json = objectMapper.writeValueAsString(session);
            stringRedisTemplate.opsForValue().set(
                    sessionKey(session.getUploadId()),
                    json,
                    UPLOAD_TTL_HOURS,
                    TimeUnit.HOURS
            );
            stringRedisTemplate.opsForZSet().add(
                    SESSION_INDEX_KEY,
                    session.getUploadId(),
                    now
            );
        } catch (Exception e) {
            throw new IllegalStateException("上传状态保存失败", e);
        }
    }

    private SongUploadSession getSession(String uploadId) {
        try {
            String json = stringRedisTemplate.opsForValue().get(sessionKey(uploadId));
            return StringUtils.hasText(json) ? objectMapper.readValue(json, SongUploadSession.class) : null;
        } catch (Exception e) {
            log.warn("读取歌曲上传会话失败，uploadId={}", uploadId, e);
            return null;
        }
    }

    private void cleanupSession(String uploadId) {
        try {
            minioService.deleteObjectsByPrefix(stagingFolder(uploadId));
        } catch (Exception e) {
            log.warn("清理歌曲上传 staging 文件失败，uploadId={}", uploadId, e);
        }
        try {
            stringRedisTemplate.delete(sessionKey(uploadId));
            stringRedisTemplate.opsForZSet().remove(SESSION_INDEX_KEY, uploadId);
        } catch (Exception e) {
            log.warn("清理歌曲上传 Redis 会话失败，uploadId={}", uploadId, e);
        }
    }

    private void safeDeleteObject(String objectName) {
        if (!StringUtils.hasText(objectName)) {
            return;
        }
        try {
            minioService.deleteObject(objectName);
        } catch (Exception e) {
            log.warn("补偿删除 MinIO 文件失败，object={}", objectName, e);
        }
    }

    private String decodeText(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return Charset.forName("GB18030").decode(ByteBuffer.wrap(bytes)).toString();
        }
    }

    private String durationValue(Double seconds) {
        if (seconds == null || seconds <= 0) {
            return null;
        }
        return String.format(Locale.ROOT, "%.2f", seconds);
    }

    private String coverExtension(String mimeType) {
        if (!StringUtils.hasText(mimeType)) {
            return "jpg";
        }
        String mime = mimeType.toLowerCase(Locale.ROOT);
        if (mime.contains("png")) {
            return "png";
        }
        if (mime.contains("webp")) {
            return "webp";
        }
        return "jpg";
    }

    private String extensionOf(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "";
        }
        String normalized = filename.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        int index = name.lastIndexOf('.');
        return index >= 0 ? name.substring(index + 1).toLowerCase(Locale.ROOT) : "";
    }

    private String extensionFromObjectName(String objectName, String fallback) {
        String extension = extensionOf(objectName);
        return StringUtils.hasText(extension) ? extension : fallback;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private boolean isValidUploadId(String uploadId) {
        if (!StringUtils.hasText(uploadId)) {
            return false;
        }
        try {
            UUID.fromString(uploadId);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String stagingFolder(String uploadId) {
        return STAGING_ROOT + "/" + uploadId;
    }

    private String sessionKey(String uploadId) {
        return SESSION_KEY_PREFIX + uploadId;
    }
}
