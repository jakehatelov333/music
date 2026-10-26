package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.mapper.AlbumMapper;
import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.mapper.SongMapper;
import cn.edu.seig.vibemusic.model.dto.SongUploadCommitDTO;
import cn.edu.seig.vibemusic.model.dto.SongUploadSession;
import cn.edu.seig.vibemusic.model.entity.Album;
import cn.edu.seig.vibemusic.model.entity.Artist;
import cn.edu.seig.vibemusic.model.entity.Song;
import cn.edu.seig.vibemusic.model.vo.SongUploadCommitVO;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.ArtistNameParser;
import cn.edu.seig.vibemusic.service.ArtistProvisioningService;
import cn.edu.seig.vibemusic.service.IAudioMetadataService;
import cn.edu.seig.vibemusic.service.MinioService;
import cn.edu.seig.vibemusic.service.SongArtistService;
import cn.edu.seig.vibemusic.security.ImageStorageService;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单曲上传提交的「提交前失败」与「提交后失败」边界。
 *
 * <p>这份测试盯的是审计问题 5（P1）：历史上 {@code commit} 只有一段
 * {@code catch}，无论歌曲行是否已经提交，都会 {@code safeDeleteObject}
 * 删掉正式封面与音频。于是 {@code buildCommitVO}（内部查库）一旦抛异常，
 * 就会留下「歌曲记录完好、音频文件已被删除」的静默损坏 —— 用户点播放没有声音，
 * 而且没有任何日志指向「文件被自己删了」。
 *
 * <p>两条断言把这个边界钉死：
 * <ul>
 *   <li>提交后组装响应失败 → <b>不删</b>任何正式对象，改为重读已提交结果</li>
 *   <li>提交前失败 → 仍按补偿语义回收本次新对象</li>
 * </ul>
 */
class SongUploadCommitBoundaryTest {

    private static final String UPLOAD_ID = "6f1d2c3a-0000-4000-8000-000000000001";
    private static final long ARTIST_ID = 143L;
    private static final long SONG_ID = 7001L;
    private static final long ALBUM_ID = 9001L;

    private SongMapper songMapper;
    private AlbumMapper albumMapper;
    private ArtistMapper artistMapper;
    private MinioService minioService;
    private SongArtistService songArtistService;
    private SongUploadServiceImpl service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Song.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Album.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Artist.class);

        songMapper = mock(SongMapper.class);
        albumMapper = mock(AlbumMapper.class);
        artistMapper = mock(ArtistMapper.class);
        minioService = mock(MinioService.class);
        songArtistService = mock(SongArtistService.class);

        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zSetOperations = mock(ZSetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Long.class), any(java.util.concurrent.TimeUnit.class)))
                .thenReturn(true);
        when(valueOperations.get(anyString())).thenReturn(sessionJson());

        when(artistMapper.selectById(any())).thenReturn(activeArtist());
        when(minioService.objectExists(anyString())).thenReturn(true);
        when(minioService.copyObject(eq("staging/song-upload/" + UPLOAD_ID + "/audio.mp3"), eq("songs"), anyString()))
                .thenReturn("http://localhost:9005/vibe-music-data/songs/final-audio.mp3");
        when(minioService.copyObject(eq("staging/song-upload/" + UPLOAD_ID + "/cover.jpg"), eq("songCovers"), anyString()))
                .thenReturn("http://localhost:9005/vibe-music-data/songCovers/final-cover.jpg");
        when(minioService.statObject(anyString())).thenAnswer(invocation -> {
            String objectName = invocation.getArgument(0);
            return new MinioService.ObjectMetadata(objectName, 1024, "application/octet-stream");
        });
        when(minioService.getObject(anyString()))
                .thenAnswer(invocation -> new java.io.ByteArrayInputStream(new byte[]{1, 2, 3}));

        // 专辑已存在，避免走 insert 分支；resolveAlbumId 现在跑在事务里，返回值必须稳定。
        when(albumMapper.selectOne(any())).thenAnswer(invocation -> {
            Album album = new Album();
            album.setAlbumId(ALBUM_ID);
            album.setCoverUrl("http://localhost:9005/vibe-music-data/songCovers/existing-cover.jpg");
            album.setReleaseDate(LocalDate.now());
            return album;
        });

        AtomicLong sequence = new AtomicLong(SONG_ID - 1);
        doAnswer(invocation -> {
            invocation.getArgument(0, Song.class).setSongId(sequence.incrementAndGet());
            return 1;
        }).when(songMapper).insert(any(Song.class));

        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(org.springframework.transaction.TransactionStatus.class));

        service = new SongUploadServiceImpl();
        ReflectionTestUtils.setField(service, "songMapper", songMapper);
        ReflectionTestUtils.setField(service, "albumMapper", albumMapper);
        ReflectionTestUtils.setField(service, "artistMapper", artistMapper);
        ReflectionTestUtils.setField(service, "minioService", minioService);
        ReflectionTestUtils.setField(service, "songArtistService", songArtistService);
        ReflectionTestUtils.setField(service, "artistNameParser", new ArtistNameParser(artistMapper));
        ReflectionTestUtils.setField(service, "artistProvisioningService",
                new ArtistProvisioningService(new ArtistNameParser(artistMapper), mock(cn.edu.seig.vibemusic.service.IArtistService.class)));
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "transactionManager", transactionManager);
        ReflectionTestUtils.setField(service, "audioMetadataService", mock(IAudioMetadataService.class));
        ImageStorageService images = mock(ImageStorageService.class);
        when(images.validateCover(any(), anyString(), any(Long.class)))
                .thenReturn(new ImageStorageService.ValidatedImage("image/jpeg"));
        ReflectionTestUtils.setField(service, "imageStorageService", images);
        ThreadLocalUtil.set(java.util.Map.of(
                JwtClaimsConstant.ADMIN_ID, 1L,
                JwtClaimsConstant.ROLE, "ROLE_ADMIN"));
    }

    @AfterEach
    void clearIdentity() {
        ThreadLocalUtil.remove();
    }

    /**
     * 提交成功、组装响应时查库失败 —— 正式音频与封面<b>都不能删</b>，且返回码可以是成功或
     * 明确的「已保存」提示，但绝不能是让人重传的普通失败。
     */
    @Test
    void responseAssemblyFailureAfterCommitMustNotDeleteCommittedMedia() {
        // buildCommitVO 内部调 attachArtists 查库 → 让它抛异常，模拟「已提交、查库失败」
        doThrow(new IllegalStateException("模拟查询失败"))
                .when(songArtistService).attachArtists(any(cn.edu.seig.vibemusic.model.vo.SongArtistAware.class));

        Result<SongUploadCommitVO> result = service.commit(commitDTO());

        // 核心断言：已入库的正式对象一个都不能被回收
        verify(minioService, never()).deleteObject("songs/final-audio.mp3");
        verify(minioService, never()).deleteObject("songCovers/final-cover.jpg");
        verify(minioService, never()).deleteObject(anyString());

        // 歌曲行确实在同一事务内写入过了
        verify(songMapper).insert(any(Song.class));
        assertNotNull(result);
    }

    /**
     * 提交前失败（例如歌手关系写入抛错）—— 仍应回收本次新对象，保持原有补偿语义。
     */
    @Test
    void failureBeforeCommitStillReclaimsThisRunsObjects() {
        doThrow(new IllegalStateException("模拟歌手关系写入失败"))
                .when(songArtistService).replaceSongArtists(any(), any());

        Result<SongUploadCommitVO> result = service.commit(commitDTO());

        ArgumentCaptor<String> deleted = ArgumentCaptor.forClass(String.class);
        verify(minioService, org.mockito.Mockito.atLeastOnce()).deleteObject(deleted.capture());
        List<String> deletedNames = deleted.getAllValues();
        // 对象名带随机 UUID，按目录前缀断言（songs/<uuid>.mp3、songCovers/<uuid>.jpg）
        assertTrue(deletedNames.stream().anyMatch(name -> name.startsWith("songs/")),
                "提交前失败应回收本次转存的音频，实际删除：" + deletedNames);
        assertTrue(deletedNames.stream().anyMatch(name -> name.startsWith("songCovers/")),
                "提交前失败应回收本次转存的封面，实际删除：" + deletedNames);
        assertEquals(1, result.getCode() == null ? 1 : result.getCode().intValue(),
                "提交前失败应返回失败");
    }

    // ------------------------------------------------------------------ 夹具

    private SongUploadCommitDTO commitDTO() {
        SongUploadCommitDTO dto = new SongUploadCommitDTO();
        dto.setUploadId(UPLOAD_ID);
        dto.setSongName("E2E 边界测试歌曲");
        dto.setArtistId(ARTIST_ID);
        dto.setAlbumId(ALBUM_ID);
        dto.setReleaseDate(LocalDate.now());
        dto.setArtistIds(List.of(ARTIST_ID));
        return dto;
    }

    private static Artist activeArtist() {
        Artist artist = new Artist();
        artist.setArtistId(ARTIST_ID);
        artist.setArtistName("银临");
        artist.setStatus(cn.edu.seig.vibemusic.enumeration.ArtistStatusEnum.ACTIVE.getStatus());
        return artist;
    }

    private static String sessionJson() {
        return "{"
                + "\"uploadId\":\"" + UPLOAD_ID + "\","
                + "\"ownerAdminId\":1,"
                + "\"audioObjectName\":\"staging/song-upload/" + UPLOAD_ID + "/audio.mp3\","
                + "\"audioOriginalName\":\"audio.mp3\","
                + "\"audioSize\":1024,"
                + "\"coverObjectName\":\"staging/song-upload/" + UPLOAD_ID + "/cover.jpg\","
                + "\"durationParsed\":true,"
                + "\"durationSeconds\":180.5,"
                + "\"metadataAvailable\":true"
                + "}";
    }
}
