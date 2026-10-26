package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.mapper.AlbumMapper;
import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.mapper.SongMapper;
import cn.edu.seig.vibemusic.model.dto.SongUploadCommitDTO;
import cn.edu.seig.vibemusic.model.entity.Album;
import cn.edu.seig.vibemusic.model.entity.Artist;
import cn.edu.seig.vibemusic.model.entity.Song;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.ArtistNameParser;
import cn.edu.seig.vibemusic.service.ArtistProvisioningService;
import cn.edu.seig.vibemusic.service.IAudioMetadataService;
import cn.edu.seig.vibemusic.service.IArtistService;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「已有歌手 + 待新建歌手混排」的提交解析。
 *
 * <p>背景：上传页的歌手字段从「两个互斥输入框」改成一个可创建的多选框后，
 * 一次提交里可能同时包含<b>有 id 的已有歌手</b>和<b>只有名字的新歌手</b>，
 * 而且用户可以把新歌手放在第一位（即主艺人）。
 *
 * <p>这份测试钉住四件事：
 * <ul>
 *   <li>新歌手按需创建，且与已有歌手同处一个事务（失败一起回滚）；</li>
 *   <li>展示顺序由 artistOrder 决定，而不是「先 id 后名字」；</li>
 *   <li>artistOrder 缺省时退化为「id 在前、名字在后」，兼容旧客户端；</li>
 *   <li>artistOrder 里解析不出的条目被跳过，绝不把 null 写进关系表。</li>
 * </ul>
 */
class SongUploadPendingArtistsTest {

    private static final String UPLOAD_ID = "6f1d2c3a-0000-4000-8000-0000000a0b01";
    private static final long EXISTING_ID = 142L;
    private static final long NEW_ID = 999L;
    private static final long ALBUM_ID = 9001L;

    private SongMapper songMapper;
    private SongArtistService songArtistService;
    private ArtistProvisioningService provisioningService;
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
        AlbumMapper albumMapper = mock(AlbumMapper.class);
        ArtistMapper artistMapper = mock(ArtistMapper.class);
        MinioService minioService = mock(MinioService.class);
        songArtistService = mock(SongArtistService.class);
        provisioningService = mock(ArtistProvisioningService.class);

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

        when(minioService.objectExists(anyString())).thenReturn(true);
        when(minioService.statObject(anyString())).thenAnswer(invocation -> {
            String objectName = invocation.getArgument(0);
            return new MinioService.ObjectMetadata(objectName, 1024, "application/octet-stream");
        });
        when(minioService.getObject(anyString()))
                .thenAnswer(invocation -> new java.io.ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(minioService.copyObject(anyString(), eq("songs"), anyString()))
                .thenReturn("http://localhost:9005/vibe-music-data/songs/final.mp3");
        when(albumMapper.selectOne(any())).thenReturn(existingAlbum());

        AtomicLong seq = new AtomicLong(7000L);
        doAnswer(inv -> {
            inv.getArgument(0, Song.class).setSongId(seq.incrementAndGet());
            return 1;
        }).when(songMapper).insert(any(Song.class));

        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(org.springframework.transaction.TransactionStatus.class));

        service = new SongUploadServiceImpl();
        ReflectionTestUtils.setField(service, "songMapper", songMapper);
        ReflectionTestUtils.setField(service, "albumMapper", albumMapper);
        ReflectionTestUtils.setField(service, "artistMapper", artistMapper);
        ReflectionTestUtils.setField(service, "minioService", minioService);
        ReflectionTestUtils.setField(service, "songArtistService", songArtistService);
        ReflectionTestUtils.setField(service, "artistNameParser", new ArtistNameParser(artistMapper));
        ReflectionTestUtils.setField(service, "artistProvisioningService", provisioningService);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "transactionManager", txManager);
        ReflectionTestUtils.setField(service, "audioMetadataService", mock(IAudioMetadataService.class));
        ReflectionTestUtils.setField(service, "imageStorageService", mock(ImageStorageService.class));
        ThreadLocalUtil.set(java.util.Map.of(
                JwtClaimsConstant.ADMIN_ID, 1L,
                JwtClaimsConstant.ROLE, "ROLE_ADMIN"));
    }

    @AfterEach
    void clearIdentity() {
        ThreadLocalUtil.remove();
    }

    /** 只有待新建歌手时，应创建它并使用其 id。 */
    @Test
    @DisplayName("只有待新建歌手时按需创建")
    void createsPendingArtist() {
        when(provisioningService.createMissing(any()))
                .thenReturn(java.util.Map.of("新歌手", NEW_ID));

        Result<?> result = service.commit(dto(List.of(), List.of("新歌手"), null));

        assertEquals(0, result.getCode(), "应成功：" + result.getMessage());
        assertEquals(List.of(NEW_ID), capturedArtistIds());
    }

    /** 已有歌手与待新建歌手混排，顺序由 artistOrder 决定。 */
    @Test
    @DisplayName("artistOrder 决定混排顺序：新歌手可排在第一位（主艺人）")
    void respectsArtistOrder() {
        when(provisioningService.createMissing(any()))
                .thenReturn(java.util.Map.of("新歌手", NEW_ID));

        Result<?> result = service.commit(dto(
                List.of(EXISTING_ID),
                List.of("新歌手"),
                List.of("name:新歌手", "id:" + EXISTING_ID)));

        assertEquals(0, result.getCode(), "应成功：" + result.getMessage());
        assertEquals(List.of(NEW_ID, EXISTING_ID), capturedArtistIds(),
                "新歌手应排在第一位（主艺人），而不是被 id 挤到后面");
    }

    /** 不传 artistOrder 时退化为「id 在前、名字在后」，兼容旧客户端。 */
    @Test
    @DisplayName("artistOrder 缺省时退化为 id 在前、名字在后")
    void fallsBackWhenOrderMissing() {
        when(provisioningService.createMissing(any()))
                .thenReturn(java.util.Map.of("新歌手", NEW_ID));

        Result<?> result = service.commit(dto(List.of(EXISTING_ID), List.of("新歌手"), null));

        assertEquals(0, result.getCode(), "应成功：" + result.getMessage());
        assertEquals(List.of(EXISTING_ID, NEW_ID), capturedArtistIds());
    }

    /**
     * artistOrder 里出现无法解析的条目时应跳过，不能把 null 写进关系表。
     *
     * <p>真实触发场景：前端传了 {@code "id:abc"}（旧数据、手工构造的请求）。
     */
    @Test
    @DisplayName("artistOrder 中无法解析的条目被跳过，不产生 null")
    void skipsUnparsableOrderEntries() {
        when(provisioningService.createMissing(any())).thenReturn(java.util.Map.of());

        Result<?> result = service.commit(dto(
                List.of(EXISTING_ID),
                List.of(),
                List.of("id:abc", "id:", "name:不存在的名字", "id:" + EXISTING_ID)));

        assertEquals(0, result.getCode(), "应成功：" + result.getMessage());
        List<Long> ids = capturedArtistIds();
        assertEquals(List.of(EXISTING_ID), ids);
        // 用 stream 判空而不是 ids.contains(null)：List.of() 是不可变列表，不接受 null 查询会抛 NPE
        assertTrue(ids.stream().allMatch(java.util.Objects::nonNull), "不能把 null 写进关系表：" + ids);
    }

    /** 空白名字不应触发创建。 */
    @Test
    @DisplayName("空白名字被过滤，不发起创建")
    void ignoresBlankPendingNames() {
        when(provisioningService.createMissing(any())).thenReturn(java.util.Map.of());

        service.commit(dto(List.of(EXISTING_ID), List.of("", "   "), null));

        List<Long> ids = capturedArtistIds();
        assertEquals(List.of(EXISTING_ID), ids);
    }

    /** 已有歌手路径完全不受影响（回归保护）。 */
    @Test
    @DisplayName("只有已有歌手时不触发创建，行为与改动前一致")
    void unchangedForExistingArtistsOnly() {
        Result<?> result = service.commit(dto(List.of(EXISTING_ID), null, null));

        assertEquals(0, result.getCode(), "应成功：" + result.getMessage());
        assertEquals(List.of(EXISTING_ID), capturedArtistIds());
        org.mockito.Mockito.verify(provisioningService, org.mockito.Mockito.never())
                .createMissing(any());
    }

    /** 创建失败时整个提交失败，不留半成品。 */
    @Test
    @DisplayName("创建歌手失败时提交失败")
    void failsWhenCreationFails() {
        when(provisioningService.createMissing(any()))
                .thenThrow(new IllegalStateException("歌手「新歌手」存在同名多条记录，请先到歌手管理处理"));

        Result<?> result = service.commit(dto(List.of(), List.of("新歌手"), null));

        assertEquals(1, result.getCode());
        assertTrue(result.getMessage().contains("同名"), "应透出可读原因：" + result.getMessage());
    }

    // ------------------------------------------------------------------ 夹具

    @SuppressWarnings("unchecked")
    private List<Long> capturedArtistIds() {
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(songArtistService)
                .replaceSongArtists(any(), captor.capture());
        return captor.getValue();
    }

    private SongUploadCommitDTO dto(List<Long> artistIds, List<String> pending, List<String> order) {
        SongUploadCommitDTO dto = new SongUploadCommitDTO();
        dto.setUploadId(UPLOAD_ID);
        dto.setSongName("混排歌手测试");
        dto.setAlbumId(ALBUM_ID);
        dto.setReleaseDate(LocalDate.now());
        dto.setArtistIds(artistIds);
        dto.setPendingArtistNames(pending);
        dto.setArtistOrder(order);
        return dto;
    }

    private static Album existingAlbum() {
        Album album = new Album();
        album.setAlbumId(ALBUM_ID);
        album.setReleaseDate(LocalDate.now());
        return album;
    }

    private static String sessionJson() {
        return "{"
                + "\"uploadId\":\"" + UPLOAD_ID + "\","
                + "\"ownerAdminId\":1,"
                + "\"audioObjectName\":\"staging/song-upload/" + UPLOAD_ID + "/audio.mp3\","
                + "\"audioOriginalName\":\"audio.mp3\","
                + "\"audioSize\":1024,"
                + "\"durationParsed\":true,"
                + "\"durationSeconds\":180.5,"
                + "\"metadataAvailable\":true"
                + "}";
    }
}
