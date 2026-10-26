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
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端草稿箱的「按 uploadId 取回上传会话」接口。
 *
 * <p>钉住三件事：
 * <ul>
 *   <li>会话还在时，返回的结构与 {@code prepare} 完全一致（音频信息、歌词内容、
 *       歌手解析结论都要在），否则草稿箱「继续」等于让管理员重新上传一遍；</li>
 *   <li>会话过期时返回 {@code data = null} 且 {@code code = 0} —— 过期是正常生命周期终点，
 *       不是错误，管理端据此静默移除条目；</li>
 *   <li>uploadId 非法时直接拒绝，且<b>不读 Redis</b>。</li>
 * </ul>
 */
class SongUploadSessionTest {

    private static final String UPLOAD_ID = "6f1d2c3a-0000-4000-8000-00000000a5e1";
    private static final String STAGING = "staging/song-upload/" + UPLOAD_ID;

    private ValueOperations<String, String> valueOperations;
    private SongUploadServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForZSet()).thenReturn(mock(ZSetOperations.class));

        service = new SongUploadServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "minioService", mock(MinioService.class));
        ReflectionTestUtils.setField(service, "coverDownloader", mock(CoverDownloader.class));
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
        ThreadLocalUtil.set(java.util.Map.of(
                JwtClaimsConstant.ADMIN_ID, 1L,
                JwtClaimsConstant.ROLE, "ROLE_ADMIN"));
    }

    @AfterEach
    void clearIdentity() {
        ThreadLocalUtil.remove();
    }

    /**
     * 会话还在：音频信息、歌词内容与封面都原样回来。
     *
     * <p>歌词内容尤其关键：管理员可能已经搜到并手工改过歌词，这些都只存在于会话里，
     * 丢了就等于白干。
     */
    @Test
    @DisplayName("会话存在时返回与 prepare 一致的结构")
    void returnsPrepareShapeForLiveSession() {
        when(valueOperations.get("song-upload:session:" + UPLOAD_ID)).thenReturn(sessionJson());

        Result<SongUploadPrepareVO> result = service.session(UPLOAD_ID);

        assertEquals(0, result.getCode(), "应成功，实际消息：" + result.getMessage());
        SongUploadPrepareVO vo = result.getData();
        assertNotNull(vo, "会话还在时不能返回空数据");
        assertEquals(UPLOAD_ID, vo.getUploadId());
        assertEquals("晴天", vo.getMetadata().getTitle());
        assertEquals("周杰伦", vo.getMetadata().getArtist());
        assertEquals("叶惠美", vo.getMetadata().getAlbum());
        assertEquals(2003, vo.getMetadata().getYear());
        assertTrue(vo.getMetadata().isDurationParsed());
        assertEquals(269.0, vo.getMetadata().getDuration());
        assertEquals("UPLOADED", vo.getMetadata().getCoverSource());
        assertEquals(STAGING + "/cover-uploaded-a.jpg", vo.getFiles().getCoverUrl());
        assertEquals("[00:00.00]故事的小黄花", vo.getFiles().getLyricsContent(),
                "管理员改过的歌词必须随会话一起回来");
        assertNotNull(vo.getArtistParsing(), "歌手解析结论也要重新给前端，否则标签会退化");
        assertTrue(vo.getArtistParsing().getCandidates() != null, "候选列表不能为 null");
    }

    /**
     * 会话过期：{@code code = 0} + {@code data = null}。
     *
     * <p>刻意不用 error：草稿过期是每一条草稿最终都会走到的正常终点，
     * 管理端进页面时会批量核对草稿，用错误码会让页面一次刷出好几条红色提示。
     */
    @Test
    @DisplayName("会话过期时返回空数据而不是错误，便于草稿箱静默清理")
    void returnsNullDataForExpiredSession() {
        when(valueOperations.get(anyString())).thenReturn(null);

        Result<SongUploadPrepareVO> result = service.session(UPLOAD_ID);

        assertEquals(0, result.getCode());
        assertNull(result.getData());
        assertTrue(result.getMessage().contains("过期"), "应说明原因，实际：" + result.getMessage());
    }

    /** 非 UUID 的 uploadId：直接拒绝，不拿它去访问 Redis。 */
    @Test
    @DisplayName("uploadId 非法时直接拒绝且不读 Redis")
    void rejectsInvalidUploadId() {
        Result<SongUploadPrepareVO> result = service.session("../../etc/passwd");

        assertEquals(1, result.getCode());
        verify(valueOperations, never()).get(anyString());
    }

    /** 读取会话必须是纯读操作：不能顺手刷新 TTL 或重建索引。 */
    @Test
    @DisplayName("读取会话不写 Redis（不续期、不改索引）")
    void sessionReadIsSideEffectFree() {
        when(valueOperations.get(anyString())).thenReturn(sessionJson());

        service.session(UPLOAD_ID);

        verify(valueOperations, never()).set(anyString(), anyString(), eq(12L), eq(java.util.concurrent.TimeUnit.HOURS));
    }

    // ------------------------------------------------------------------ 夹具

    private static String sessionJson() {
        return "{"
                + "\"uploadId\":\"" + UPLOAD_ID + "\","
                + "\"createdAt\":1,"
                + "\"lastActivityAt\":2,"
                + "\"ownerAdminId\":1,"
                + "\"audioObjectName\":\"" + STAGING + "/audio.mp3\","
                + "\"audioUrl\":\"http://localhost:9005/vibe-music-data/" + STAGING + "/audio.mp3\","
                + "\"audioOriginalName\":\"晴天.mp3\","
                + "\"audioContentType\":\"audio/mpeg\","
                + "\"audioSize\":9000000,"
                + "\"audioFormat\":\"MP3\","
                + "\"durationSeconds\":269.0,"
                + "\"durationText\":\"04:29\","
                + "\"durationParsed\":true,"
                + "\"title\":\"晴天\","
                + "\"artist\":\"周杰伦\","
                + "\"album\":\"叶惠美\","
                + "\"year\":2003,"
                + "\"metadataAvailable\":true,"
                + "\"hasEmbeddedCover\":false,"
                + "\"coverObjectName\":\"" + STAGING + "/cover-uploaded-a.jpg\","
                + "\"coverUrl\":\"" + STAGING + "/cover-uploaded-a.jpg\","
                + "\"coverSource\":\"UPLOADED\","
                + "\"lyricsObjectName\":\"" + STAGING + "/lyrics.lrc\","
                + "\"lyricsContent\":\"[00:00.00]故事的小黄花\""
                + "}";
    }
}
