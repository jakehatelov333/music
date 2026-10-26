package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.constant.MediaCleanupSourceType;
import cn.edu.seig.vibemusic.enumeration.ArtistStatusEnum;
import cn.edu.seig.vibemusic.mapper.AlbumMapper;
import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.mapper.UserFavoriteMapper;
import cn.edu.seig.vibemusic.model.dto.ArtistUpdateDTO;
import cn.edu.seig.vibemusic.model.entity.Artist;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.security.ImageStorageService;
import cn.edu.seig.vibemusic.service.MediaCleanupService;
import cn.edu.seig.vibemusic.service.MinioService;
import cn.edu.seig.vibemusic.service.SongArtistService;
import cn.edu.seig.vibemusic.service.artist.ArtistProfileService;
import cn.edu.seig.vibemusic.service.cover.CoverDownloader;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编辑歌手时替换头像。
 *
 * <p>这份测试的核心是一条容易被忽略的约束：<b>不传头像 ≠ 清空头像</b>。
 * 编辑框每次提交都是整份表单，而头像并不在表单字段里（它走独立的文件上传或
 * QQ 下载）。如果空值被当成"清空"，用户每改一次简介就会把头像弄丢 ——
 * 这是最难在人工测试里发现、但线上一定会踩到的一类缺陷。
 */
class ArtistUpdateAvatarTest {

    private static final long ARTIST_ID = 148L;
    private static final String OLD_URL =
            "http://localhost:9005/vibe-music-data/artists/old-blob";
    private static final String NEW_URL =
            "http://localhost:9005/vibe-music-data/artists/new-blob";
    private static final String QQ_MID = "0025NhlN2yWrP4";

    private ArtistMapper artistMapper;
    private MinioService minioService;
    private ImageStorageService imageStorageService;
    private MediaCleanupService mediaCleanupService;
    private SongArtistService songArtistService;
    private ArtistProfileService artistProfileService;
    private ArtistServiceImpl service;
    private Artist existing;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Artist.class);

        artistMapper = mock(ArtistMapper.class);
        minioService = mock(MinioService.class);
        imageStorageService = new ImageStorageService(minioService);
        mediaCleanupService = mock(MediaCleanupService.class);
        songArtistService = mock(SongArtistService.class);
        artistProfileService = mock(ArtistProfileService.class);

        existing = new Artist();
        existing.setArtistId(ARTIST_ID);
        existing.setArtistName("灰原穷");
        existing.setAvatar(OLD_URL);
        existing.setStatus(ArtistStatusEnum.ACTIVE.getStatus());
        when(artistMapper.selectById(ARTIST_ID)).thenReturn(existing);
        when(artistMapper.updateById(any(Artist.class))).thenReturn(1);
        when(minioService.uploadBytes(any(byte[].class), eq("artists"), anyString(), eq("image/jpeg")))
                .thenReturn(NEW_URL);

        service = new ArtistServiceImpl();
        ReflectionTestUtils.setField(service, "artistMapper", artistMapper);
        ReflectionTestUtils.setField(service, "albumMapper", mock(AlbumMapper.class));
        ReflectionTestUtils.setField(service, "userFavoriteMapper", mock(UserFavoriteMapper.class));
        ReflectionTestUtils.setField(service, "minioService", minioService);
        ReflectionTestUtils.setField(service, "songArtistService", songArtistService);
        ReflectionTestUtils.setField(service, "mediaCleanupService", mediaCleanupService);
        ReflectionTestUtils.setField(service, "artistProfileService", artistProfileService);
        ReflectionTestUtils.setField(service, "imageStorageService", imageStorageService);
        ReflectionTestUtils.setField(service, "redisTemplate", mock(RedisTemplate.class));
    }

    // ------------------------------------------------------------------ 不传头像

    @Test
    @DisplayName("不传头像：只改文字字段，avatar 列完全不动")
    void updateWithoutAvatarKeepsExistingAvatar() {
        Result<?> result = service.updateArtist(dto(), null, null, null);

        assertEquals(0, result.getCode(), "应成功：" + result.getMessage());
        Artist updated = capturedUpdate();
        // 关键断言：avatar 没有被写进更新语句
        assertNull(updated.getAvatar(), "不传头像时不应设置 avatar 字段");
        assertEquals("灰原穷改", updated.getArtistName());
        verify(minioService, never()).uploadBytes(any(byte[].class), anyString(), anyString(), anyString());
        verify(mediaCleanupService, never()).enqueueForId(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("不传头像时不会把旧头像登记清理 —— 否则会删掉正在用的图")
    void updateWithoutAvatarDoesNotEnqueueCleanup() {
        service.updateArtist(dto(), null, null, null);

        verify(mediaCleanupService, never())
                .enqueueForId(eq(OLD_URL), anyString(), any(), anyString());
    }

    // ------------------------------------------------------------------ 上传文件

    @Test
    @DisplayName("上传新头像：写库后把旧头像登记清理队列，不就地删除")
    void uploadAvatarReplacesAndEnqueuesOld() {
        Result<?> result = service.updateArtist(dto(), image("a.jpg", "image/jpeg"), null, null);

        assertEquals(0, result.getCode(), "应成功：" + result.getMessage());
        assertEquals(NEW_URL, capturedUpdate().getAvatar());
        verify(mediaCleanupService, times(1)).enqueueForId(
                OLD_URL, MediaCleanupSourceType.ARTIST_AVATAR_REPLACE, ARTIST_ID, "替换歌手头像");
        // 旧头像可能被别的行共用，绝不就地删
        verify(minioService, never()).deleteFile(OLD_URL);
    }

    @Test
    @DisplayName("头像没变（同一个 URL）时不登记清理，避免把自己删掉")
    void unchangedAvatarIsNotEnqueued() {
        existing.setAvatar(NEW_URL);

        service.updateArtist(dto(), image("a.jpg", "image/jpeg"), null, null);

        verify(mediaCleanupService, never()).enqueueForId(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("原来没有头像时不登记清理")
    void noPreviousAvatarMeansNoCleanup() {
        existing.setAvatar(null);

        service.updateArtist(dto(), image("a.jpg", "image/jpeg"), null, null);

        verify(mediaCleanupService, never()).enqueueForId(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("非法图片被拒绝，且不写库、不登记清理")
    void invalidAvatarRejected() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.updateArtist(dto(), image("a.pdf", "application/pdf"), null, null));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertTrue(String.valueOf(error.getReason()).contains("图片"));
        verify(artistMapper, never()).updateById(any(Artist.class));
        verify(mediaCleanupService, never()).enqueueForId(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("超大图片被拒绝")
    void oversizedAvatarRejected() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.updateArtist(dto(), oversizedImage("big.jpg", "image/jpeg"), null, null));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertTrue(String.valueOf(error.getReason()).contains("2 MB"));
        verify(artistMapper, never()).updateById(any(Artist.class));
    }

    @Test
    @DisplayName("改库失败时回收刚上传的新对象，不留孤儿")
    void updateFailureRemovesUploadedObject() {
        when(artistMapper.updateById(any(Artist.class))).thenReturn(0);

        Result<?> result = service.updateArtist(dto(), image("a.jpg", "image/jpeg"), null, null);

        assertEquals(1, result.getCode(), "应失败");
        verify(minioService, times(1)).deleteFile(NEW_URL);
        verify(mediaCleanupService, never()).enqueueForId(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("改库抛异常时同样回收新对象，并向上抛出")
    void updateThrowsRemovesUploadedObject() {
        when(artistMapper.updateById(any(Artist.class)))
                .thenThrow(new IllegalStateException("数据库炸了"));

        assertThrows(IllegalStateException.class,
                () -> service.updateArtist(dto(), image("a.jpg", "image/jpeg"), null, null));

        verify(minioService, times(1)).deleteFile(NEW_URL);
    }

    // ------------------------------------------------------------------ QQ 下载

    @Test
    @DisplayName("传 qqSingerMid：由服务端下载头像并落库")
    void qqAvatarIsDownloadedAndStored() {
        byte[] bytes = cn.edu.seig.vibemusic.service.TestImages.png();
        when(artistProfileService.downloadAvatar(eq(QQ_MID), any(), any()))
                .thenReturn(new CoverDownloader.Result(bytes, "image/png", null));

        Result<?> result = service.updateArtist(dto(), null, QQ_MID, 800);

        assertEquals(0, result.getCode(), "应成功：" + result.getMessage());
        assertEquals(NEW_URL, capturedUpdate().getAvatar());
        // QQ often returns PNG even when callers expect JPEG; the server must
        // decode the bytes, re-encode, and store a generated .jpg object.
        verify(minioService).uploadBytes(any(byte[].class), eq("artists"),
                org.mockito.ArgumentMatchers.matches("[0-9a-f-]+\\.jpg"), eq("image/jpeg"));
        verify(mediaCleanupService, times(1)).enqueueForId(
                OLD_URL, MediaCleanupSourceType.ARTIST_AVATAR_REPLACE, ARTIST_ID, "替换歌手头像");
    }

    @Test
    @DisplayName("QQ 头像下载失败时整体放弃 —— 修改场景不能静默保存其它字段")
    void qqAvatarFailureAbortsUpdate() {
        // 与新增不同：新增时取不到头像仍可建歌手（头像只是可选信息）；
        // 修改时用户是明确要求换头像的，静默跳过会让人以为换了
        when(artistProfileService.downloadAvatar(anyString(), any(), any()))
                .thenReturn(new CoverDownloader.Result(null, null, "下载失败"));

        Result<?> result = service.updateArtist(dto(), null, QQ_MID, 500);

        assertEquals(1, result.getCode(), "应失败");
        assertTrue(String.valueOf(result.getMessage()).contains("头像下载失败"));
        verify(artistMapper, never()).updateById(any(Artist.class));
        verify(mediaCleanupService, never()).enqueueForId(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("非法 mid 走下载失败路径，不会写库")
    void invalidMidAbortsUpdate() {
        Result<?> result = service.updateArtist(dto(), null, "abc", 500);

        assertEquals(1, result.getCode(), "应失败");
        verify(artistMapper, never()).updateById(any(Artist.class));
    }

    @Test
    @DisplayName("qqSingerMid 优先于上传文件")
    void qqTakesPrecedenceOverUpload() {
        when(artistProfileService.downloadAvatar(anyString(), any(), any()))
                .thenReturn(new CoverDownloader.Result(cn.edu.seig.vibemusic.service.TestImages.jpeg(),
                        "image/jpeg", null));

        service.updateArtist(dto(), image("a.jpg", "image/jpeg"), QQ_MID, 500);

        verify(minioService).uploadBytes(any(byte[].class), eq("artists"), anyString(), eq("image/jpeg"));
    }

    // ------------------------------------------------------------------ 其它校验

    @Test
    @DisplayName("歌手不存在时给出明确错误")
    void missingArtistFailsCleanly() {
        when(artistMapper.selectById(ARTIST_ID)).thenReturn(null);

        Result<?> result = service.updateArtist(dto(), image("a.jpg", "image/jpeg"), null, null);

        assertEquals(1, result.getCode(), "应失败");
        verify(artistMapper, never()).updateById(any(Artist.class));
        verify(minioService, never()).uploadBytes(any(byte[].class), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("artistId 为空时拒绝，而不是空指针")
    void nullArtistIdFailsCleanly() {
        ArtistUpdateDTO dto = dto();
        dto.setArtistId(null);

        Result<?> result = service.updateArtist(dto, null, null, null);

        assertEquals(1, result.getCode(), "应失败");
        verify(artistMapper, never()).updateById(any(Artist.class));
    }

    @Test
    @DisplayName("旧的两参入口仍然可用，等价于不换头像")
    void legacyOverloadStillWorks() {
        Result<?> result = service.updateArtist(dto());

        assertEquals(0, result.getCode(), "应成功：" + result.getMessage());
        assertNull(capturedUpdate().getAvatar());
    }

    @Test
    @DisplayName("改名后重建派生展示串")
    void rebuildsDerivedNames() {
        service.updateArtist(dto(), null, null, null);

        verify(songArtistService, times(1)).rebuildArtistNamesForArtist(ARTIST_ID);
    }

    // ------------------------------------------------------------------ helpers

    private Artist capturedUpdate() {
        ArgumentCaptor<Artist> captor = ArgumentCaptor.forClass(Artist.class);
        verify(artistMapper).updateById(captor.capture());
        return captor.getValue();
    }

    private ArtistUpdateDTO dto() {
        ArtistUpdateDTO dto = new ArtistUpdateDTO();
        dto.setArtistId(ARTIST_ID);
        dto.setArtistName("灰原穷改");
        dto.setGender(0);
        dto.setBirth(LocalDate.of(1990, 1, 1));
        dto.setArea("中国");
        dto.setIntroduction("改过的简介");
        dto.setStatus(ArtistStatusEnum.ACTIVE.getStatus());
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
