package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.constant.MediaCleanupSourceType;
import cn.edu.seig.vibemusic.enumeration.ArtistStatusEnum;
import cn.edu.seig.vibemusic.mapper.AlbumMapper;
import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.mapper.UserFavoriteMapper;
import cn.edu.seig.vibemusic.model.dto.ArtistAddDTO;
import cn.edu.seig.vibemusic.model.entity.Artist;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.security.ImageStorageService;
import cn.edu.seig.vibemusic.service.MediaCleanupService;
import cn.edu.seig.vibemusic.service.MinioService;
import cn.edu.seig.vibemusic.service.SongArtistService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
 * 新增歌手时同时上传头像，以及替换头像时的旧对象处理。
 *
 * <p>钉住的行为：
 * <ul>
 *   <li>带头像新增 → 头像 URL 落在同一条记录上，且返回新 id；</li>
 *   <li>不带头像新增 → 行为与旧接口一致（avatar 为 null），返回新 id；</li>
 *   <li>头像不合规（超大 / 非图片）→ 拒绝，且<b>不</b>写库、<b>不</b>上传；</li>
 *   <li>插入失败 → 刚上传的对象被回收，不留孤儿；</li>
 *   <li>替换头像 → 旧头像登记到清理队列，而<b>不是</b>就地删除。</li>
 * </ul>
 */
class ArtistAvatarLifecycleTest {

    private static final long NEW_ID = 7001L;
    private static final long EXISTING_ID = 142L;
    private static final String UPLOADED_URL =
            "http://localhost:9005/vibe-music-data/artists/0000-0000-blob";
    private static final String OLD_URL =
            "http://localhost:9005/vibe-music-data/artists/1111-1111-blob";

    private ArtistMapper artistMapper;
    private MinioService minioService;
    private ImageStorageService imageStorageService;
    private MediaCleanupService mediaCleanupService;
    private ArtistServiceImpl service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Artist.class);

        artistMapper = mock(ArtistMapper.class);
        minioService = mock(MinioService.class);
        imageStorageService = new ImageStorageService(minioService);
        mediaCleanupService = mock(MediaCleanupService.class);

        AtomicLong seq = new AtomicLong(NEW_ID - 1);
        doAnswer(inv -> {
            inv.getArgument(0, Artist.class).setArtistId(seq.incrementAndGet());
            return 1;
        }).when(artistMapper).insert(any(Artist.class));

        when(minioService.uploadBytes(any(byte[].class), eq("artists"), anyString(), eq("image/jpeg")))
                .thenReturn(UPLOADED_URL);

        service = new ArtistServiceImpl();
        ReflectionTestUtils.setField(service, "artistMapper", artistMapper);
        ReflectionTestUtils.setField(service, "albumMapper", mock(AlbumMapper.class));
        ReflectionTestUtils.setField(service, "userFavoriteMapper", mock(UserFavoriteMapper.class));
        ReflectionTestUtils.setField(service, "minioService", minioService);
        ReflectionTestUtils.setField(service, "songArtistService", mock(SongArtistService.class));
        ReflectionTestUtils.setField(service, "mediaCleanupService", mediaCleanupService);
        ReflectionTestUtils.setField(service, "imageStorageService", imageStorageService);
        ReflectionTestUtils.setField(service, "redisTemplate", mock(RedisTemplate.class));
    }

    // ------------------------------------------------------------------ 新增

    @Test
    @DisplayName("带头像新增：头像落在同一条记录上，并返回新 id")
    void addArtistWithAvatarStoresUrlAndReturnsId() {
        Result<?> result = service.addArtist(dto("新歌手"), image("avatar.jpg", "image/jpeg"));

        assertEquals(0, result.getCode(), "应成功：" + result.getMessage());
        assertEquals(NEW_ID, result.getData(), "应返回新歌手 id");

        Artist saved = capturedInsert();
        assertEquals("新歌手", saved.getArtistName());
        assertEquals(UPLOADED_URL, saved.getAvatar());
        assertEquals(ArtistStatusEnum.ACTIVE.getStatus(), saved.getStatus());
        // 业务字段不该被头像流程弄丢
        assertEquals(1, saved.getGender());
        assertEquals(LocalDate.of(1990, 5, 4), saved.getBirth());
        assertEquals("中国", saved.getArea());
    }

    @Test
    @DisplayName("不带头像新增：avatar 保持 null，行为与旧接口一致")
    void addArtistWithoutAvatarLeavesAvatarNull() {
        Result<?> result = service.addArtist(dto("新歌手"), null);

        assertEquals(0, result.getCode(), "应成功：" + result.getMessage());
        assertEquals(NEW_ID, result.getData());
        assertNull(capturedInsert().getAvatar(), "没传头像就不该有 URL");
        verify(minioService, never()).uploadBytes(any(byte[].class), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("空文件视同没传头像，不触发上传")
    void emptyAvatarIsTreatedAsAbsent() {
        MockMultipartFile empty = new MockMultipartFile("avatar", "empty.jpg", "image/jpeg", new byte[0]);

        Result<?> result = service.addArtist(dto("新歌手"), empty);

        assertEquals(0, result.getCode(), "应成功：" + result.getMessage());
        assertNull(capturedInsert().getAvatar());
        verify(minioService, never()).uploadBytes(any(byte[].class), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("超过 2 MB 的头像被拒绝，且不写库、不上传")
    void oversizedAvatarIsRejected() {
        MultipartFile huge = oversizedImage("huge.jpg", "image/jpeg");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.addArtist(dto("新歌手"), huge));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertTrue(String.valueOf(error.getReason()).contains("2 MB"),
                "提示应说明体积上限：" + error.getReason());
        verify(artistMapper, never()).insert(any(Artist.class));
        verify(minioService, never()).uploadBytes(any(byte[].class), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("非图片类型被拒绝，且不写库、不上传")
    void nonImageAvatarIsRejected() {
        MultipartFile pdf = image("resume.pdf", "application/pdf");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.addArtist(dto("新歌手"), pdf));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertTrue(String.valueOf(error.getReason()).contains("图片"),
                "提示应说明格式要求：" + error.getReason());
        verify(artistMapper, never()).insert(any(Artist.class));
        verify(minioService, never()).uploadBytes(any(byte[].class), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Content-Type 为空但字节是真实 JPEG 时接受，不信任客户端 MIME")
    void nullContentTypeDoesNotOverrideValidBytes() {
        MultipartFile valid = image("x.jpg", null);

        Result<?> result = service.addArtist(dto("新歌手"), valid);

        assertEquals(0, result.getCode(), "应成功：" + result.getMessage());
        assertEquals(UPLOADED_URL, capturedInsert().getAvatar());
        verify(minioService).uploadBytes(any(byte[].class), eq("artists"), anyString(), eq("image/jpeg"));
    }

    @Test
    @DisplayName("插入失败时回收刚上传的对象，不留孤儿")
    void insertFailureRemovesUploadedObject() {
        doThrow(new IllegalStateException("数据库炸了")).when(artistMapper).insert(any(Artist.class));

        assertThrows(IllegalStateException.class,
                () -> service.addArtist(dto("新歌手"), image("a.jpg", "image/jpeg")));

        verify(minioService, times(1)).deleteFile(UPLOADED_URL);
    }

    @Test
    @DisplayName("回收失败不掩盖原始插入异常")
    void cleanupFailureDoesNotMaskInsertException() {
        IllegalStateException root = new IllegalStateException("数据库炸了");
        doThrow(root).when(artistMapper).insert(any(Artist.class));
        doThrow(new RuntimeException("MinIO 也炸了")).when(minioService).deleteFile(UPLOADED_URL);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.addArtist(dto("新歌手"), image("a.jpg", "image/jpeg")));

        assertSame(root, thrown, "应抛出原始的插入异常");
    }

    @Test
    @DisplayName("同名歌手已存在时直接拒绝，连头像都不上传")
    void duplicateNameRejectedBeforeUpload() {
        when(artistMapper.selectCount(any())).thenReturn(1L);

        Result<?> result = service.addArtist(dto("周杰伦"), image("a.jpg", "image/jpeg"));

        assertEquals(1, result.getCode(), "应失败");
        verify(minioService, never()).uploadBytes(any(byte[].class), anyString(), anyString(), anyString());
        verify(artistMapper, never()).insert(any(Artist.class));
    }

    @Test
    @DisplayName("旧的两参入口仍然可用，等价于不带头像")
    void legacySingleArgOverloadStillWorks() {
        Result<?> result = service.addArtist(dto("新歌手"));

        assertEquals(0, result.getCode(), "应成功：" + result.getMessage());
        assertNull(capturedInsert().getAvatar());
    }

    // ------------------------------------------------------------------ 替换

    @Test
    @DisplayName("替换头像：旧对象登记清理队列，不就地删除")
    void replaceAvatarEnqueuesOldObject() {
        Artist existing = new Artist();
        existing.setArtistId(EXISTING_ID);
        existing.setArtistName("周杰伦");
        existing.setAvatar(OLD_URL);
        when(artistMapper.selectById(EXISTING_ID)).thenReturn(existing);
        when(artistMapper.updateById(any(Artist.class))).thenReturn(1);

        Result<?> result = service.updateArtistAvatar(EXISTING_ID, UPLOADED_URL);

        assertEquals(0, result.getCode(), "应成功：" + result.getMessage());
        verify(mediaCleanupService, times(1))
                .enqueueForId(OLD_URL, MediaCleanupSourceType.ARTIST_AVATAR_REPLACE, EXISTING_ID, "替换歌手头像");
        // 关键：不再直接删文件
        verify(minioService, never()).deleteFile(anyString());
        assertEquals(UPLOADED_URL, existing.getAvatar());
    }

    @Test
    @DisplayName("原来没有头像时不登记清理任务")
    void replaceWithoutPreviousAvatarEnqueuesNothing() {
        Artist existing = new Artist();
        existing.setArtistId(EXISTING_ID);
        existing.setAvatar(null);
        when(artistMapper.selectById(EXISTING_ID)).thenReturn(existing);
        when(artistMapper.updateById(any(Artist.class))).thenReturn(1);

        Result<?> result = service.updateArtistAvatar(EXISTING_ID, UPLOADED_URL);

        assertEquals(0, result.getCode(), "应成功：" + result.getMessage());
        verify(mediaCleanupService, never()).enqueueForId(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("头像没变时不登记清理任务，避免把自己删掉")
    void unchangedAvatarIsNotEnqueued() {
        Artist existing = new Artist();
        existing.setArtistId(EXISTING_ID);
        existing.setAvatar(OLD_URL);
        when(artistMapper.selectById(EXISTING_ID)).thenReturn(existing);
        when(artistMapper.updateById(any(Artist.class))).thenReturn(1);

        Result<?> result = service.updateArtistAvatar(EXISTING_ID, OLD_URL);

        assertEquals(0, result.getCode(), "应成功：" + result.getMessage());
        verify(mediaCleanupService, never()).enqueueForId(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("歌手不存在时给出明确错误，而不是空指针")
    void replaceOnMissingArtistFailsCleanly() {
        when(artistMapper.selectById(EXISTING_ID)).thenReturn(null);

        Result<?> result = service.updateArtistAvatar(EXISTING_ID, UPLOADED_URL);

        assertEquals(1, result.getCode(), "应失败");
        verify(artistMapper, never()).updateById(any(Artist.class));
        verify(mediaCleanupService, never()).enqueueForId(anyString(), anyString(), any(), anyString());
    }

    // ------------------------------------------------------------------ helpers

    private Artist capturedInsert() {
        ArgumentCaptor<Artist> captor = ArgumentCaptor.forClass(Artist.class);
        verify(artistMapper).insert(captor.capture());
        Artist captured = captor.getValue();
        assertNotNull(captured, "应有一条插入");
        return captured;
    }

    private ArtistAddDTO dto(String name) {
        ArtistAddDTO dto = new ArtistAddDTO();
        dto.setArtistName(name);
        dto.setGender(1);
        dto.setBirth(LocalDate.of(1990, 5, 4));
        dto.setArea("中国");
        dto.setIntroduction("测试用简介");
        return dto;
    }

    private MultipartFile image(String fileName, String contentType) {
        byte[] bytes = "image/png".equals(contentType)
                ? cn.edu.seig.vibemusic.service.TestImages.png()
                : cn.edu.seig.vibemusic.service.TestImages.jpeg();
        return new MockMultipartFile("avatar", fileName, contentType, bytes);
    }

    private MultipartFile oversizedImage(String fileName, String contentType) {
        byte[] bytes = new byte[(int) cn.edu.seig.vibemusic.security.MediaPolicy.MAX_AVATAR_BYTES + 1];
        return new MockMultipartFile("avatar", fileName, contentType, bytes);
    }
}
