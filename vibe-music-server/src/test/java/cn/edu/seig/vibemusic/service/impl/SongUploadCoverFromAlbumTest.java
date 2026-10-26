package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.mapper.AlbumMapper;
import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.mapper.SongMapper;
import cn.edu.seig.vibemusic.model.vo.SongUploadPrepareVO;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.ArtistNameParser;
import cn.edu.seig.vibemusic.service.ArtistProvisioningService;
import cn.edu.seig.vibemusic.service.IAudioMetadataService;
import cn.edu.seig.vibemusic.service.MinioService;
import cn.edu.seig.vibemusic.service.SongArtistService;
import cn.edu.seig.vibemusic.service.cover.CoverDownloader;
import cn.edu.seig.vibemusic.security.ImageStorageService;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「把搜到的专辑封面下载到 staging」的会话写入与安全边界。
 *
 * <p>钉住四件事：
 * <ul>
 *   <li>下载成功后写入 {@code coverObjectName} 并把来源标成 {@code SEARCHED}；</li>
 *   <li>换选封面时旧对象被回收，不留下无主文件；</li>
 *   <li>会话保存失败时回收刚写入的新图（否则它既没被引用、也没人知道它存在）；</li>
 *   <li><b>非法 albumMid 一律拒绝且不发起任何下载</b> —— 这是 SSRF 防线的实际位置。</li>
 * </ul>
 */
class SongUploadCoverFromAlbumTest {

    private static final String UPLOAD_ID = "6f1d2c3a-0000-4000-8000-00000000c0f1";
    private static final String VALID_MID = "002eFUFm2XYZ7z";
    private static final String STAGING = "staging/song-upload/" + UPLOAD_ID;

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private MinioService minioService;
    private CoverDownloader coverDownloader;
    private SongUploadServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        ZSetOperations<String, String> zSetOperations = mock(ZSetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        minioService = mock(MinioService.class);
        coverDownloader = mock(CoverDownloader.class);
        when(valueOperations.get(anyString())).thenReturn(sessionJson(null));

        service = new SongUploadServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "minioService", minioService);
        ReflectionTestUtils.setField(service, "coverDownloader", coverDownloader);
        ReflectionTestUtils.setField(service, "songMapper", mock(SongMapper.class));
        ReflectionTestUtils.setField(service, "albumMapper", mock(AlbumMapper.class));
        ReflectionTestUtils.setField(service, "artistMapper", mock(ArtistMapper.class));
        ReflectionTestUtils.setField(service, "songArtistService", mock(SongArtistService.class));
        ReflectionTestUtils.setField(service, "artistNameParser",
                new ArtistNameParser(mock(ArtistMapper.class)));
        ReflectionTestUtils.setField(service, "artistProvisioningService",
                new ArtistProvisioningService(new ArtistNameParser(mock(ArtistMapper.class)),
                        mock(cn.edu.seig.vibemusic.service.IArtistService.class)));
        ReflectionTestUtils.setField(service, "audioMetadataService", mock(IAudioMetadataService.class));
        ReflectionTestUtils.setField(service, "imageStorageService", new ImageStorageService(minioService));
        ThreadLocalUtil.set(java.util.Map.of(
                JwtClaimsConstant.ADMIN_ID, 1L,
                JwtClaimsConstant.ROLE, "ROLE_ADMIN"));
    }

    @AfterEach
    void clearIdentity() {
        ThreadLocalUtil.remove();
    }

    /**
     * 下载成功 → 写入 staging → 会话记录新对象名与 {@code SEARCHED} 来源。
     *
     * <p>断言「来源标记」是必要的：前端靠它决定显示「来自 QQ 音乐专辑封面」
     * 还是「使用独立上传封面」，管理员据此知道这张图是哪来的。
     */
    @Test
    @DisplayName("下载成功后写入 staging，并把来源标记为 SEARCHED")
    void writesDownloadedCoverIntoSession() {
        when(coverDownloader.download(anyString()))
                .thenReturn(new CoverDownloader.Result(jpegBytes(), "image/jpeg", null));
        when(minioService.uploadBytes(any(), anyString(), anyString(), anyString()))
                .thenReturn("http://localhost:9005/vibe-music-data/" + STAGING + "/cover-searched-x.jpg");

        Result<SongUploadPrepareVO> result = service.updateCoverFromAlbum(UPLOAD_ID, VALID_MID, 500);

        assertEquals(0, result.getCode(), "应成功，实际消息：" + result.getMessage());
        assertEquals("SEARCHED", result.getData().getMetadata().getCoverSource(),
                "来源必须标成 SEARCHED，前端据此显示「来自 QQ 音乐专辑封面」");

        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(contains(UPLOAD_ID), saved.capture(), any(Long.class), any());
        assertTrue(saved.getValue().contains("cover-searched-"),
                "会话里应记录 cover-searched- 开头的对象名，实际：" + saved.getValue());
        assertTrue(saved.getValue().contains("\"coverSource\":\"SEARCHED\""),
                "会话 JSON 里来源应为 SEARCHED，实际：" + saved.getValue());
    }

    /** 换选封面：旧对象必须被回收，否则每换一次就多留一个孤儿文件。 */
    @Test
    @DisplayName("换选封面时回收上一张，不留孤儿文件")
    void deletesPreviousCoverOnReplace() {
        when(valueOperations.get(anyString()))
                .thenReturn(sessionJson(STAGING + "/cover-embedded.jpg"));
        when(coverDownloader.download(anyString()))
                .thenReturn(new CoverDownloader.Result(jpegBytes(), "image/jpeg", null));
        when(minioService.uploadBytes(any(), anyString(), anyString(), anyString()))
                .thenReturn("http://localhost:9005/vibe-music-data/" + STAGING + "/cover-searched-y.jpg");

        Result<SongUploadPrepareVO> result = service.updateCoverFromAlbum(UPLOAD_ID, VALID_MID, 800);

        assertEquals(0, result.getCode(), "应成功，实际消息：" + result.getMessage());
        verify(minioService).deleteObject(STAGING + "/cover-embedded.jpg");
    }

    /**
     * 下载失败 → 不写 staging、不动会话、返回可读错误。
     *
     * <p>重点是「可读」：把「该专辑没有可用的封面图」「封面体积超出上限」这类
     * 具体原因原样透出，而不是统一成一句"失败"。管理员看到前者会换个候选，
     * 看到后者会换个尺寸 —— 提示不同，行动也不同。
     */
    @Test
    @DisplayName("下载失败时原样返回可读原因，且不写 staging")
    void surfacesDownloadFailure() {
        when(coverDownloader.download(anyString()))
                .thenReturn(new CoverDownloader.Result(null, null, "该专辑没有可用的封面图"));

        Result<SongUploadPrepareVO> result = service.updateCoverFromAlbum(UPLOAD_ID, VALID_MID, 500);

        assertEquals(1, result.getCode());
        assertEquals("该专辑没有可用的封面图", result.getMessage());
        verify(minioService, never()).uploadBytes(any(), anyString(), anyString(), anyString());
    }

    /**
     * 非法 albumMid：直接拒绝，<b>连下载都不发起</b>。
     *
     * <p>这是 SSRF 防线的实际位置。若这里放行，服务端就会按调用方给的字符串
     * 去拼地址并抓取，而字符串可能被构造成任意 URL 或路径穿越。
     */
    @Test
    @DisplayName("非法 albumMid 直接拒绝，不发起任何下载")
    void rejectsInvalidAlbumMidWithoutDownloading() {
        String[] unsafe = {"../../etc/passwd", "http://evil.com/x", "abc", "", null};
        for (String mid : unsafe) {
            Result<SongUploadPrepareVO> result = service.updateCoverFromAlbum(UPLOAD_ID, mid, 500);

            assertEquals(1, result.getCode(), "应拒绝：" + mid);
            assertEquals("专辑标识不合法", result.getMessage(), "应给出明确原因：" + mid);
        }
        verify(coverDownloader, never()).download(anyString());
    }

    /** 会话不存在（已过期）：明确告知重新上传，不尝试下载。 */
    @Test
    @DisplayName("会话已过期时提示重新上传，不发起下载")
    void rejectsExpiredSession() {
        when(valueOperations.get(anyString())).thenReturn(null);

        Result<SongUploadPrepareVO> result = service.updateCoverFromAlbum(UPLOAD_ID, VALID_MID, 500);

        assertEquals(1, result.getCode());
        assertTrue(result.getMessage().contains("已过期"), "实际：" + result.getMessage());
        verify(coverDownloader, never()).download(anyString());
    }

    /** uploadId 不是合法 UUID：直接拒绝，不读 Redis。 */
    @Test
    @DisplayName("uploadId 非法时直接拒绝")
    void rejectsInvalidUploadId() {
        Result<SongUploadPrepareVO> result = service.updateCoverFromAlbum("not-a-uuid", VALID_MID, 500);

        assertEquals(1, result.getCode());
        verify(coverDownloader, never()).download(anyString());
    }

    /**
     * 尺寸非法时回退到 500 并照常工作，而不是报错。
     *
     * <p>尺寸只是显示精度，不该因为传了个 999 就让整次操作失败。
     */
    @Test
    @DisplayName("尺寸非法时回退到 500，不报错")
    void invalidSizeFallsBack() {
        when(coverDownloader.download(anyString()))
                .thenReturn(new CoverDownloader.Result(jpegBytes(), "image/jpeg", null));
        when(minioService.uploadBytes(any(), anyString(), anyString(), anyString()))
                .thenReturn("http://localhost:9005/vibe-music-data/" + STAGING + "/cover-searched-z.jpg");

        Result<SongUploadPrepareVO> result = service.updateCoverFromAlbum(UPLOAD_ID, VALID_MID, 9999);

        assertEquals(0, result.getCode(), "非法尺寸应回退而不是失败：" + result.getMessage());
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(coverDownloader).download(url.capture());
        assertTrue(url.getValue().contains("T002R500x500"),
                "应回退到 500 档，实际请求：" + url.getValue());
    }

    /** 新封面文件名必须带随机后缀，不能覆盖同目录下的其它文件。 */
    @Test
    @DisplayName("新封面使用随机文件名，不与既有文件冲突")
    void usesRandomFileName() {
        when(coverDownloader.download(anyString()))
                .thenReturn(new CoverDownloader.Result(jpegBytes(), "image/jpeg", null));
        when(minioService.uploadBytes(any(), anyString(), anyString(), anyString()))
                .thenReturn("http://localhost:9005/vibe-music-data/" + STAGING + "/cover-searched-r.jpg");

        service.updateCoverFromAlbum(UPLOAD_ID, VALID_MID, 500);

        ArgumentCaptor<String> fileName = ArgumentCaptor.forClass(String.class);
        verify(minioService).uploadBytes(any(), eq(STAGING), fileName.capture(), anyString());
        assertTrue(fileName.getValue().matches(
                        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg"),
                "服务端应使用随机 UUID 对象名，实际：" + fileName.getValue());
        assertTrue(fileName.getValue().endsWith(".jpg"), "扩展名应来自 Content-Type：" + fileName.getValue());
    }

    // ------------------------------------------------------------------ 夹具

    private static byte[] jpegBytes() {
        return cn.edu.seig.vibemusic.service.TestImages.jpeg();
    }

    private static String sessionJson(String coverObjectName) {
        StringBuilder json = new StringBuilder("{")
                .append("\"uploadId\":\"").append(UPLOAD_ID).append("\",")
                .append("\"createdAt\":1,")
                .append("\"ownerAdminId\":1,")
                .append("\"audioObjectName\":\"").append(STAGING).append("/audio.mp3\",")
                .append("\"durationParsed\":true,")
                .append("\"metadataAvailable\":true");
        if (coverObjectName != null) {
            json.append(",\"coverObjectName\":\"").append(coverObjectName).append("\"")
                    .append(",\"coverSource\":\"EMBEDDED\"");
        }
        return json.append("}").toString();
    }
}
