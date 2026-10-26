package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.enumeration.ArtistStatusEnum;
import cn.edu.seig.vibemusic.mapper.AlbumMapper;
import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.mapper.SongMapper;
import cn.edu.seig.vibemusic.model.dto.AlbumUploadCommitDTO;
import cn.edu.seig.vibemusic.model.dto.ArtistAddDTO;
import cn.edu.seig.vibemusic.model.dto.AudioMetadataDTO;
import cn.edu.seig.vibemusic.model.entity.Album;
import cn.edu.seig.vibemusic.model.entity.Artist;
import cn.edu.seig.vibemusic.model.entity.Song;
import cn.edu.seig.vibemusic.model.vo.AlbumUploadPrepareVO;
import cn.edu.seig.vibemusic.model.vo.AlbumUploadPendingArtistsVO;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.security.ImageStorageService;
import cn.edu.seig.vibemusic.service.ArtistNameParser;
import cn.edu.seig.vibemusic.service.ArtistProvisioningService;
import cn.edu.seig.vibemusic.service.IAlbumUploadService;
import cn.edu.seig.vibemusic.service.IArtistService;
import cn.edu.seig.vibemusic.service.IAudioMetadataService;
import cn.edu.seig.vibemusic.service.MinioService;
import cn.edu.seig.vibemusic.service.SongArtistService;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 专辑上传提交的「逐首歌手 + 确认后创建」两段式流程。
 *
 * <p>这份测试盯的是回归点：<b>解析出来的多歌手不能在入库时退化</b>。历史上
 * {@code commit} 只认 {@code artistIds}/{@code artistId}，前端又只传文本，
 * 结果每首歌都被赋成专辑主艺人 —— 预览里明明是对的，入库后就没了。
 *
 * <p>同时钉住「确认前不写任何数据」：未带 {@code allowCreate} 时只能返回 409 清单，
 * 不能建歌手、不能转存音频、不能插歌。
 */
class AlbumUploadCommitArtistTest {

    private static final String UPLOAD_ID = "e2e-upload-id";
    private static final long EXISTING_ARTIST_ID = 143L;
    private static final String EXISTING_ARTIST_NAME = "银临";
    private static final String NEW_ARTIST_NAME = "E2E临时歌手A";

    private final List<Artist> artistStore = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong(200);

    private AlbumMapper albumMapper;
    private ArtistMapper artistMapper;
    private SongMapper songMapper;
    private MinioService minioService;
    private SongArtistService songArtistService;
    private cn.edu.seig.vibemusic.service.MediaCleanupService mediaCleanupService;
    private IArtistService artistService;
    private IAudioMetadataService metadataService;
    private ImageStorageService imageStorageService;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> redisValueOperations;
    private AlbumUploadServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        artistStore.clear();
        sequence.set(200);
        artistStore.add(artist(EXISTING_ARTIST_ID, EXISTING_ARTIST_NAME));

        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Artist.class);

        albumMapper = mock(AlbumMapper.class);
        artistMapper = mock(ArtistMapper.class);
        songMapper = mock(SongMapper.class);
        minioService = mock(MinioService.class);
        songArtistService = mock(SongArtistService.class);
        mediaCleanupService = mock(cn.edu.seig.vibemusic.service.MediaCleanupService.class);
        metadataService = mock(IAudioMetadataService.class);
        imageStorageService = mock(ImageStorageService.class);

        ThreadLocalUtil.set(Map.of(
                JwtClaimsConstant.ADMIN_ID, 1L,
                JwtClaimsConstant.ROLE, "ROLE_ADMIN"));

        when(artistMapper.selectById(any())).thenAnswer(invocation -> {
            Object id = invocation.getArgument(0);
            return artistStore.stream()
                    .filter(item -> item.getArtistId().equals(id))
                    .findFirst().orElse(null);
        });
        when(artistMapper.selectList(any())).thenAnswer(invocation -> matching(invocation.getArgument(0)));

        artistService = mock(IArtistService.class);
        when(artistService.addArtist(any(ArtistAddDTO.class))).thenAnswer(invocation -> {
            ArtistAddDTO addDTO = invocation.getArgument(0);
            Artist created = artist(sequence.incrementAndGet(), addDTO.getArtistName());
            artistStore.add(created);
            return Result.success("新增成功");
        });

        ArtistNameParser parser = new ArtistNameParser(artistMapper);
        ArtistProvisioningService provisioningService =
                new ArtistProvisioningService(parser, artistService);

        redisTemplate = mock(StringRedisTemplate.class);
        redisValueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(redisValueOperations);
        when(redisValueOperations.get(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return key.startsWith("album-upload:owner:") ? "1" : stagingSessionJson();
        });

        when(minioService.objectExists(anyString())).thenReturn(true);
        when(minioService.copyObject(anyString(), anyString(), anyString()))
                .thenReturn("http://minio.local/object");
        when(minioService.copyObject(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("http://minio.local/object");
        when(minioService.statObject(anyString())).thenAnswer(invocation -> {
            String object = invocation.getArgument(0);
            return object.contains("album-cover")
                    ? new MinioService.ObjectMetadata(object, 2048, "image/jpeg")
                    : new MinioService.ObjectMetadata(object, 1024, "audio/mpeg");
        });
        when(minioService.getObject(anyString())).thenReturn(InputStream.nullInputStream());
        when(metadataService.parse(any(InputStream.class), anyString(), anyLong()))
                .thenReturn(new AudioMetadataDTO());
        when(imageStorageService.validateCover(any(InputStream.class), anyString(), anyLong()))
                .thenReturn(new ImageStorageService.ValidatedImage("image/jpeg"));

        doAnswer(invocation -> {
            invocation.getArgument(0, Album.class).setAlbumId(9001L);
            return 1;
        }).when(albumMapper).insert(any(Album.class));

        AtomicLong songId = new AtomicLong(7000);
        doAnswer(invocation -> {
            invocation.getArgument(0, Song.class).setSongId(songId.incrementAndGet());
            return 1;
        }).when(songMapper).insert(any(Song.class));

        service = new AlbumUploadServiceImpl();
        ReflectionTestUtils.setField(service, "albumMapper", albumMapper);
        ReflectionTestUtils.setField(service, "artistMapper", artistMapper);
        ReflectionTestUtils.setField(service, "songMapper", songMapper);
        ReflectionTestUtils.setField(service, "minioService", minioService);
        ReflectionTestUtils.setField(service, "songArtistService", songArtistService);
        ReflectionTestUtils.setField(service, "artistNameParser", parser);
        ReflectionTestUtils.setField(service, "artistProvisioningService", provisioningService);
        ReflectionTestUtils.setField(service, "mediaCleanupService", mediaCleanupService);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "metadataService", metadataService);
        ReflectionTestUtils.setField(service, "imageStorageService", imageStorageService);
    }

    @AfterEach
    void clearIdentity() {
        ThreadLocalUtil.remove();
    }

    @Test
    void unconfirmedCommitOnlyReturnsTheMissingArtistListAndWritesNothing() {
        Result<Object> result = service.commit(commitDTO(false));

        assertEquals(Result.CODE_CONFIRMATION_REQUIRED, result.getCode().intValue());
        AlbumUploadPendingArtistsVO pending = (AlbumUploadPendingArtistsVO) result.getData();
        assertEquals(List.of(NEW_ARTIST_NAME), pending.getMissingArtists());
        assertTrue(pending.getAmbiguousArtists().isEmpty());
        assertEquals(2, pending.getSongCount());

        verify(artistService, never()).addArtist(any(ArtistAddDTO.class));
        verify(albumMapper, never()).insert(any(Album.class));
        verify(songMapper, never()).insert(any(Song.class));
        verify(minioService, never()).copyObject(anyString(), anyString(), anyString());
    }

    @Test
    void confirmedCommitKeepsEveryPerSongArtistInOrder() {
        Result<Object> result = service.commit(commitDTO(true));

        assertEquals(0, result.getCode().intValue());
        Long createdId = artistStore.stream()
                .filter(item -> NEW_ARTIST_NAME.equals(item.getArtistName()))
                .findFirst().orElseThrow().getArtistId();
        assertNotNull(createdId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> artistIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(songArtistService, times(2)).replaceSongArtists(any(), artistIdsCaptor.capture());
        // 顺序即展示顺序：主艺人 银临 在前，新创建的歌手在后；两首歌都要完整保留
        assertEquals(List.of(EXISTING_ARTIST_ID, createdId), artistIdsCaptor.getAllValues().get(0));
        assertEquals(List.of(EXISTING_ARTIST_ID, createdId), artistIdsCaptor.getAllValues().get(1));

        verify(artistService, times(1)).addArtist(any(ArtistAddDTO.class));
        verify(songMapper, times(2)).insert(any(Song.class));
        verify(minioService, times(2)).copyObject(anyString(), anyString(), anyString());
    }

    @Test
    void explicitArtistIdsWinOverTheRawArtistNameText() {
        AlbumUploadCommitDTO dto = commitDTO(false);
        // 前端（或管理员）已经明确选了歌手 → 不再按文本解析，也就不会要求创建任何歌手
        dto.getSongs().forEach(song -> song.setArtistIds(List.of(EXISTING_ARTIST_ID)));

        Result<Object> result = service.commit(dto);

        assertEquals(0, result.getCode().intValue());
        verify(artistService, never()).addArtist(any(ArtistAddDTO.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> artistIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(songArtistService, times(2)).replaceSongArtists(any(), artistIdsCaptor.capture());
        assertEquals(List.of(EXISTING_ARTIST_ID), artistIdsCaptor.getAllValues().get(0));
    }

    @Test
    void clientSuppliedCoverUrlIsNeverPersisted() {
        AlbumUploadCommitDTO dto = commitDTO(false);
        dto.getSongs().forEach(song -> song.setArtistIds(List.of(EXISTING_ARTIST_ID)));
        dto.getAlbum().setCoverUrl("http://minio.local/vibe-music-data/songs/victim.mp3");

        Result<Object> result = service.commit(dto);

        assertEquals(0, result.getCode().intValue(), "提交应成功：" + result.getMessage());
        ArgumentCaptor<Album> albumCaptor = ArgumentCaptor.forClass(Album.class);
        verify(albumMapper).insert(albumCaptor.capture());
        assertNull(albumCaptor.getValue().getCoverUrl());

        ArgumentCaptor<Song> songCaptor = ArgumentCaptor.forClass(Song.class);
        verify(songMapper, times(2)).insert(songCaptor.capture());
        assertTrue(songCaptor.getAllValues().stream()
                .allMatch(song -> song.getCoverUrl() == null));
    }

    @Test
    void existingAlbumKeepsItsServerStoredCoverWhenClientSuppliesAnotherUrl() {
        String existingCover = "http://minio.local/vibe-music-data/albumCovers/server-cover.jpg";
        Album existing = new Album();
        existing.setAlbumId(9001L);
        existing.setCoverUrl(existingCover);
        when(albumMapper.selectOne(any())).thenReturn(existing);
        AlbumUploadCommitDTO dto = commitDTO(false);
        dto.getSongs().forEach(song -> song.setArtistIds(List.of(EXISTING_ARTIST_ID)));
        dto.getAlbum().setCoverUrl("http://minio.local/vibe-music-data/songs/victim.mp3");

        Result<Object> result = service.commit(dto);

        assertEquals(0, result.getCode().intValue(), "提交应成功：" + result.getMessage());
        verify(albumMapper, never()).insert(any(Album.class));
        verify(albumMapper, never()).update(any(), any());
        ArgumentCaptor<Song> songCaptor = ArgumentCaptor.forClass(Song.class);
        verify(songMapper, times(2)).insert(songCaptor.capture());
        assertTrue(songCaptor.getAllValues().stream()
                .allMatch(song -> existingCover.equals(song.getCoverUrl())));
    }

    @Test
    void sessionMarksFilesMissingFromStagingAsNotUploaded() {
        // 直传中断：会话里仍有两个文件，但第二个对象并没有落到 MinIO
        when(minioService.objectExists("staging/album-upload/" + UPLOAD_ID + "/02 第二首.mp3"))
                .thenReturn(false);

        Result<Object> result = service.session(UPLOAD_ID);

        assertEquals(0, result.getCode().intValue());
        Map<?, ?> data = (Map<?, ?>) result.getData();
        List<?> files = (List<?>) data.get("files");
        assertEquals(2, files.size());

        Map<?, ?> first = (Map<?, ?>) files.get(0);
        assertEquals("01 第一首.mp3", first.get("fileName"));
        assertEquals(true, first.get("uploaded"));

        Map<?, ?> second = (Map<?, ?>) files.get(1);
        assertEquals("02 第二首.mp3", second.get("fileName"));
        assertEquals(false, second.get("uploaded"));
    }

    @Test
    void parseRefusesSessionWithMissingStagedFile() {
        when(minioService.objectExists("staging/album-upload/" + UPLOAD_ID + "/02 第二首.mp3"))
                .thenReturn(false);

        Result<AlbumUploadPrepareVO> result = service.parseStaging(UPLOAD_ID);

        assertNotEquals(0, result.getCode().intValue());
        assertTrue(result.getMessage().contains("尚未上传完成"));
        // 解析阶段不能落库，也不能改动会话
        verify(albumMapper, never()).insert(any(Album.class));
        verify(songMapper, never()).insert(any(Song.class));
    }

    @Test
    void expiredRedisSessionIsNoLongerServedFromProcessMemory() {
        // Redis key 已过期：会话必须真的消失，不能再从任何进程内缓存里读回来
        when(redisValueOperations.get(anyString())).thenReturn(null);

        Result<Object> session = service.session(UPLOAD_ID);
        assertNotEquals(0, session.getCode().intValue());
        assertTrue(session.getMessage().contains("过期"));

        Result<Object> commit = service.commit(commitDTO(true));
        assertNotEquals(0, commit.getCode().intValue());
        verify(albumMapper, never()).insert(any(Album.class));
        verify(songMapper, never()).insert(any(Song.class));
        verify(minioService, never()).copyObject(anyString(), anyString(), anyString());
    }

    @Test
    void cleanupReclaimsStagingFilesOnceRedisSessionIsGone() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(minioService.listStagedObjects(anyString()))
                .thenReturn(List.of(new MinioService.StagedObject(
                        "staging/album-upload/" + UPLOAD_ID + "/01 第一首.mp3",
                        java.time.ZonedDateTime.now().minusHours(2))));

        int cleaned = service.cleanupOrphanStaging();

        assertEquals(1, cleaned);
        verify(minioService).deleteObjectsByPrefix("staging/album-upload/" + UPLOAD_ID + "/");
    }

    /**
     * prepare 的窗口：文件已落盘、会话还没写。只按「无会话」判断会误删正在上传的专辑。
     */
    @Test
    void cleanupSkipsStagingWrittenMomentsAgo() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(minioService.listStagedObjects(anyString()))
                .thenReturn(List.of(new MinioService.StagedObject(
                        "staging/album-upload/" + UPLOAD_ID + "/01 第一首.mp3",
                        java.time.ZonedDateTime.now().minusSeconds(5))));

        int cleaned = service.cleanupOrphanStaging();

        assertEquals(0, cleaned);
        verify(minioService, never()).deleteObjectsByPrefix(anyString());
    }

    @Test
    void createSessionReturnsConfiguredExpiry() {
        long before = System.currentTimeMillis();

        Result<Object> result = service.createSession(List.of(
                new IAlbumUploadService.AlbumUploadFileRequest("01 第一首.mp3", 1024, "audio/mpeg", false)));

        assertEquals(0, result.getCode().intValue());
        Map<?, ?> data = (Map<?, ?>) result.getData();
        // 默认配置 12 小时：前端据此展示「剩余时间」并做离线兜底
        assertEquals(12L, ((Number) data.get("expiresInHours")).longValue());
        long expiresAt = ((Number) data.get("expiresAt")).longValue();
        assertTrue(expiresAt >= before + 12 * 3600_000L - 5_000);
        verify(redisValueOperations, times(2)).set(anyString(), anyString(), eq(12L), eq(TimeUnit.HOURS));
    }

    @Test
    void sessionReportsRemainingTtlFromRedis() {
        // 签约/完成单个文件会续期，剩余时间必须以 Redis 真实 TTL 为准，而不是「创建时间 + 12h」
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(1800L);
        long before = System.currentTimeMillis();

        Result<Object> result = service.session(UPLOAD_ID);

        Map<?, ?> data = (Map<?, ?>) result.getData();
        long expiresAt = ((Number) data.get("expiresAt")).longValue();
        assertTrue(expiresAt >= before + 1_800_000L - 1_000);
        assertTrue(expiresAt <= System.currentTimeMillis() + 1_800_000L + 1_000);
        assertEquals(1L, ((Number) data.get("expiresInHours")).longValue());
    }

    // ------------------------------------------------------------------ 夹具

    private AlbumUploadCommitDTO commitDTO(boolean allowCreate) {
        AlbumUploadCommitDTO dto = new AlbumUploadCommitDTO();
        dto.setUploadId(UPLOAD_ID);
        dto.setAllowCreate(allowCreate);

        AlbumUploadCommitDTO.AlbumInfo album = new AlbumUploadCommitDTO.AlbumInfo();
        album.setAlbumName("E2E专辑-临时删除");
        album.setArtistId(EXISTING_ARTIST_ID);
        album.setReleaseDate(LocalDate.now());
        dto.setAlbum(album);

        List<AlbumUploadCommitDTO.SongInfo> songs = new ArrayList<>();
        songs.add(songInfo("01 第一首.mp3", "第一首", 1));
        songs.add(songInfo("02 第二首.mp3", "第二首", 2));
        dto.setSongs(songs);
        return dto;
    }

    private AlbumUploadCommitDTO.SongInfo songInfo(String fileName, String songName, int trackNumber) {
        AlbumUploadCommitDTO.SongInfo song = new AlbumUploadCommitDTO.SongInfo();
        song.setFileName(fileName);
        song.setSongName(songName);
        song.setArtistName(EXISTING_ARTIST_NAME + "、" + NEW_ARTIST_NAME);
        song.setTrackNumber(trackNumber);
        song.setDiscNumber(1);
        return song;
    }

    /**
     * 重传同名专辑并带新封面时，被换下的旧封面必须登记清理任务。
     *
     * <p>旧实现只把 {@code cover_url} 覆盖掉，旧对象再没有任何行引用它，
     * 也不在 staging 前缀下（孤儿清扫扫不到），只能永久占着存储。
     */
    @Test
    void replacingAlbumCoverEnqueuesOldCoverForCleanup() {
        String oldCover = "http://minio.local/vibe-music-data/albumCovers/old-cover.jpg";
        when(redisValueOperations.get(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return key.startsWith("album-upload:owner:") ? "1" : stagingSessionJsonWithCover();
        });
        Album existing = new Album();
        existing.setAlbumId(9001L);
        existing.setCoverUrl(oldCover);
        when(albumMapper.selectOne(any())).thenReturn(existing);
        when(minioService.copyObject(anyString(), eq("albumCovers"), anyString(), eq("image/jpeg")))
                .thenReturn("http://minio.local/vibe-music-data/albumCovers/new-cover.jpg");

        Result<Object> result = service.commit(commitDTO(true));

        assertEquals(0, result.getCode().intValue(), "提交应成功：" + result.getMessage());
        verify(mediaCleanupService).enqueueForId(
                eq(oldCover),
                eq(cn.edu.seig.vibemusic.constant.MediaCleanupSourceType.ALBUM_COVER_REPLACE),
                eq(9001L),
                anyString());
    }

    /**
     * 放弃草稿：文件删不掉时<b>必须保留会话并报错</b>，不能谎报「已清理」。
     *
     * <p>会话是唯一记录「这次上传还没收尾」的地方；先删会话再删文件、失败了也不吭声，
     * 管理员会以为删干净了，而文件只能等孤儿清扫兜底。
     */
    @Test
    void abortKeepsSessionWhenStagingDeletionFails() {
        doThrow(new RuntimeException("目录清理失败: 网络超时"))
                .when(minioService).deleteObjectsByPrefix(anyString());

        Result<Object> result = service.abort(UPLOAD_ID);

        assertNotEquals(0, result.getCode().intValue(), "失败必须显式报错，不能返回成功");
        verify(redisTemplate, never()).delete(anyList());
    }

    /** 放弃草稿成功：先删文件、再删会话。 */
    @Test
    void abortDeletesStagingThenSession() {
        Result<Object> result = service.abort(UPLOAD_ID);

        assertEquals(0, result.getCode().intValue(), "应成功：" + result.getMessage());
        var order = org.mockito.Mockito.inOrder(minioService, redisTemplate);
        order.verify(minioService).deleteObjectsByPrefix("staging/album-upload/" + UPLOAD_ID + "/");
        order.verify(redisTemplate).delete(List.of(
                "album-upload:session:" + UPLOAD_ID,
                "album-upload:owner:" + UPLOAD_ID));
    }

    /** 会话已过期：不能仅凭 UUID 删除 staging 文件。 */
    @Test
    void abortRejectsWhenSessionAlreadyGone() {
        when(redisValueOperations.get(anyString())).thenReturn(null);

        Result<Object> result = service.abort(UPLOAD_ID);

        assertNotEquals(0, result.getCode().intValue());
        verify(minioService, never()).deleteObjectsByPrefix(anyString());
        verify(redisTemplate, never()).delete(anyList());
    }

    @Test
    void clearRejectsAnotherAdministrator() {
        ThreadLocalUtil.set(Map.of(
                JwtClaimsConstant.ADMIN_ID, 2L,
                JwtClaimsConstant.ROLE, "ROLE_ADMIN"));

        Result<Object> result = service.clear(UPLOAD_ID);

        assertNotEquals(0, result.getCode().intValue());
        verify(minioService, never()).deleteObjectsByPrefix(anyString());
        verify(redisTemplate, never()).delete(anyList());
    }

    @Test
    void clearDeletesOwnedSession() {
        Result<Object> result = service.clear(UPLOAD_ID);

        assertEquals(0, result.getCode().intValue());
        verify(minioService).deleteObjectsByPrefix("staging/album-upload/" + UPLOAD_ID + "/");
        verify(redisTemplate).delete(List.of(
                "album-upload:session:" + UPLOAD_ID,
                "album-upload:owner:" + UPLOAD_ID));
    }

    /** 与 {@code AlbumUploadServiceImpl.StagedSong} 的 Jackson 形态一致（Redis 会话里的暂存条目）。 */
    private static String stagingSessionJson() {
        return "["
                + stagedEntry("01 第一首.mp3") + ","
                + stagedEntry("02 第二首.mp3")
                + "]";
    }

    /** 带一个封面条目的暂存会话：提交时会被转存到 albumCovers。 */
    private static String stagingSessionJsonWithCover() {
        return "["
                + stagedEntry("01 第一首.mp3") + ","
                + stagedEntry("02 第二首.mp3") + ","
                + "{\"object\":\"staging/album-upload/" + UPLOAD_ID + "/album-cover.jpg\","
                + "\"url\":\"\",\"fileName\":\"cover.jpg\",\"size\":2048,"
                + "\"metadata\":{\"durationSeconds\":null}}"
                + "]";
    }

    private static String stagedEntry(String fileName) {
        return "{\"object\":\"staging/album-upload/" + UPLOAD_ID + "/" + fileName + "\","
                + "\"url\":\"\",\"fileName\":\"" + fileName + "\",\"size\":1024,"
                + "\"metadata\":{\"durationSeconds\":180.5,\"durationText\":\"03:00\"}}";
    }

    private static Artist artist(long id, String name) {
        Artist artist = new Artist();
        artist.setArtistId(id);
        artist.setArtistName(name);
        artist.setStatus(ArtistStatusEnum.ACTIVE.getStatus());
        return artist;
    }

    /**
     * 内存库替身：按「查询条件里出现过的字符串」做等值过滤，等价于
     * {@code WHERE name = ? AND status = ?}（出现 ACTIVE 参数时同时按状态过滤）。
     * MyBatis-Plus 的参数是懒注册的，必须先取一次 SQL 片段。
     */
    private List<Artist> matching(Object wrapperArgument) {
        if (!(wrapperArgument instanceof AbstractWrapper<?, ?, ?> wrapper)) {
            return List.of();
        }
        wrapper.getSqlSegment();
        Set<String> values = wrapper.getParamNameValuePairs().values().stream()
                .filter(String.class::isInstance)
                .map(String::valueOf)
                .collect(Collectors.toSet());
        boolean activeOnly = values.contains(ArtistStatusEnum.ACTIVE.getStatus());
        return artistStore.stream()
                .filter(item -> values.contains(item.getArtistName()))
                .filter(item -> !activeOnly || ArtistStatusEnum.isActive(item.getStatus()))
                .toList();
    }
}
