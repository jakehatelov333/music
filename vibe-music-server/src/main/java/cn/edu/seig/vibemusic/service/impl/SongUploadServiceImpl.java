package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.mapper.AlbumMapper;
import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.mapper.SongMapper;
import cn.edu.seig.vibemusic.enumeration.LyricStatusEnum;
import cn.edu.seig.vibemusic.model.dto.ArtistAddDTO;
import cn.edu.seig.vibemusic.model.dto.AudioMetadataDTO;
import cn.edu.seig.vibemusic.model.dto.SongUploadCommitDTO;
import cn.edu.seig.vibemusic.model.dto.SongUploadSession;
import cn.edu.seig.vibemusic.model.entity.Album;
import cn.edu.seig.vibemusic.model.entity.Artist;
import cn.edu.seig.vibemusic.model.entity.Song;
import cn.edu.seig.vibemusic.model.vo.SongUploadCommitVO;
import cn.edu.seig.vibemusic.model.vo.SongUploadFilesVO;
import cn.edu.seig.vibemusic.model.vo.SongUploadMetadataVO;
import cn.edu.seig.vibemusic.model.vo.SongUploadPrepareVO;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.IAudioMetadataService;
import cn.edu.seig.vibemusic.service.IArtistService;
import cn.edu.seig.vibemusic.service.ISongUploadService;
import cn.edu.seig.vibemusic.service.MinioService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
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
    private static final long STAGING_TTL_MILLIS = TimeUnit.HOURS.toMillis(24);
    private static final long SESSION_TTL_HOURS = 48L;
    private static final Set<String> AUDIO_EXTENSIONS = Set.of("mp3", "flac", "wav");
    private static final Set<String> COVER_EXTENSIONS = Set.of("jpg", "jpeg", "png");
    private static final Set<String> LYRICS_EXTENSIONS = Set.of("lrc", "txt");

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
    private IArtistService artistService;

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
                coverSource = "EMBEDDED";
            } catch (RuntimeException e) {
                coverWarning = "内嵌封面提取失败，请手动上传封面。";
                log.warn("内嵌封面上传失败，uploadId={}", uploadId, e);
            }
        }

        long now = System.currentTimeMillis();
        SongUploadSession session = SongUploadSession.builder()
                .uploadId(uploadId)
                .createdAt(now)
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
                session.setCoverSource("UPLOADED");
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
        try {
            Artist artist = resolveArtist(dto);
            boolean artistCreated = false;
            if (artist == null) {
                artist = createArtist(dto.getArtistName());
                artistCreated = true;
            }
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
                    artist.getArtistId(),
                    dto.getAlbum(),
                    dto.getAlbumId(),
                    releaseDate,
                    finalCoverUrl
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
                    .setArtistId(artist.getArtistId())
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

            if (songMapper.insert(song) != 1) {
                throw new IllegalStateException("歌曲信息保存失败");
            }

            cleanupSession(dto.getUploadId());
            return Result.success("歌曲上传成功", buildCommitVO(song, artist, artistCreated));
        } catch (Exception e) {
            safeDeleteObject(finalCoverObjectName);
            safeDeleteObject(finalAudioObjectName);
            log.error("歌曲正式保存失败，uploadId={}", dto.getUploadId(), e);
            return Result.error(StringUtils.hasText(e.getMessage()) ? e.getMessage() : "歌曲保存失败，请重试");
        } finally {
            stringRedisTemplate.delete(lockKey);
        }
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
        long cutoff = System.currentTimeMillis() - STAGING_TTL_MILLIS;
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

    private Artist createArtist(String artistName) {
        if (!StringUtils.hasText(artistName)) {
            throw new IllegalArgumentException("歌手名不能为空");
        }
        String normalizedName = artistName.trim();
        ArtistAddDTO addDTO = new ArtistAddDTO();
        addDTO.setArtistName(normalizedName);
        Result addResult = artistService.addArtist(addDTO);
        if (addResult.getCode() != 0) {
            Artist existing = matchArtist(normalizedName);
            if (existing != null) {
                return existing;
            }
            throw new IllegalStateException(addResult.getMessage());
        }
        Artist created = matchArtist(normalizedName);
        if (created == null) {
            throw new IllegalStateException("歌手创建失败");
        }
        return created;
    }

    private Artist resolveArtist(SongUploadCommitDTO dto) {
        if (dto.getArtistId() != null) {
            Artist artist = artistMapper.selectById(dto.getArtistId());
            if (artist != null) {
                return artist;
            }
        }
        if (!StringUtils.hasText(dto.getArtistName())) {
            return null;
        }
        List<Artist> artists = artistMapper.selectList(
                new QueryWrapper<Artist>().eq("name", dto.getArtistName().trim()).last("LIMIT 1")
        );
        return artists.isEmpty() ? null : artists.get(0);
    }

    private Long resolveAlbumId(Long artistId, String albumName, Long albumId,
                                LocalDate releaseDate, String coverUrl) {
        if (albumId != null) {
            return albumId;
        }
        if (artistId == null || !StringUtils.hasText(albumName)) {
            return null;
        }

        String normalizedName = albumName.trim();
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
        return vo;
    }

    private SongUploadCommitVO buildCommitVO(Song song, Artist artist, boolean artistCreated) {
        SongUploadCommitVO vo = new SongUploadCommitVO();
        vo.setSongId(song.getSongId());
        vo.setSongName(song.getSongName());
        vo.setArtistId(artist.getArtistId());
        vo.setArtistName(artist.getArtistName());
        vo.setArtistCreated(artistCreated);
        vo.setAlbum(song.getAlbum());
        vo.setDuration(song.getDuration());
        vo.setCoverUrl(song.getCoverUrl());
        vo.setAudioUrl(song.getAudioUrl());
        vo.setReleaseTime(song.getReleaseTime());
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
        List<Artist> artists = artistMapper.selectList(
                new QueryWrapper<Artist>().eq("name", artistName.trim()).last("LIMIT 1")
        );
        return artists.isEmpty() ? null : artists.get(0);
    }

    private void saveSession(SongUploadSession session) {
        try {
            String json = objectMapper.writeValueAsString(session);
            stringRedisTemplate.opsForValue().set(
                    sessionKey(session.getUploadId()),
                    json,
                    SESSION_TTL_HOURS,
                    TimeUnit.HOURS
            );
            stringRedisTemplate.opsForZSet().add(
                    SESSION_INDEX_KEY,
                    session.getUploadId(),
                    session.getCreatedAt()
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
