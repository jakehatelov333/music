package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.mapper.AlbumMapper;
import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.mapper.SongMapper;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.ArtistNameParser;
import cn.edu.seig.vibemusic.service.ArtistProvisioningService;
import cn.edu.seig.vibemusic.service.IAudioMetadataService;
import cn.edu.seig.vibemusic.service.MinioService;
import cn.edu.seig.vibemusic.service.SongArtistService;
import cn.edu.seig.vibemusic.service.cover.CoverDownloader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「放弃草稿」必须真的把暂存资源删掉。
 *
 * <p>钉住两件最容易退化的行为：
 * <ul>
 *   <li>MinIO 删除失败时，<b>绝不能</b>把 Redis 会话删掉 —— 会话是唯一记录这些文件存在的地方，
 *       它一没了，文件就永久变成无人知晓的孤儿（定时任务也找不到）；</li>
 *   <li>删除成功时才清理会话与索引，且失败要向上报错，不能谎报「已清空」。</li>
 * </ul>
 */
class SongUploadClearTest {

    private static final String UPLOAD_ID = "6f1d2c3a-0000-4000-8000-00000000c11a";
    private static final String STAGING = "staging/song-upload/" + UPLOAD_ID;

    private ValueOperations<String, String> valueOperations;
    private ZSetOperations<String, String> zSetOperations;
    private MinioService minioService;
    private SongUploadServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        zSetOperations = mock(ZSetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        minioService = mock(MinioService.class);

        service = new SongUploadServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "minioService", minioService);
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
    }

    /** 正常路径：文件删掉、会话与索引一起清掉，并报告成功。 */
    @Test
    @DisplayName("清理成功时删除 staging 文件、会话与索引")
    void clearsFilesAndSessionOnSuccess() {
        when(valueOperations.get(anyString())).thenReturn(sessionJson());

        Result result = service.clear(UPLOAD_ID);

        assertEquals(0, result.getCode(), "应成功，实际消息：" + result.getMessage());
        verify(minioService).deleteObjectsByPrefix(STAGING);
        verify(valueOperations).get("song-upload:session:" + UPLOAD_ID);
        // 会话与索引都必须清掉，否则定时任务会一直在索引里看到这条
        verify(zSetOperations).remove("song-upload:session-index", UPLOAD_ID);
    }

    /**
     * 删除失败：<b>会话必须保留</b>，且返回可读错误。
     *
     * <p>这是历史上真正的泄漏点：旧实现两步各自 try/catch，MinIO 一失败 Redis 照样被删，
     * 而 Redis 是唯一记录 staging 目录内容的地方，删了之后就再没有任何路径能清理这些文件。
     */
    @Test
    @DisplayName("MinIO 删除失败时保留会话，且不谎报成功")
    void keepsSessionWhenFileDeletionFails() {
        when(valueOperations.get(anyString())).thenReturn(sessionJson());
        doThrow(new RuntimeException("目录清理失败: 网络超时"))
                .when(minioService).deleteObjectsByPrefix(anyString());

        Result result = service.clear(UPLOAD_ID);

        assertEquals(1, result.getCode(), "失败必须显式报错，不能返回成功");
        assertTrue(result.getMessage().contains("清理失败"), "应给出可读原因：" + result.getMessage());
        // 会话与索引都不能动 —— 留着才能让下一次清理重试
        verify(zSetOperations, never()).remove(anyString(), anyString());
    }

    /** 会话已过期但文件还在：仍要尽力删一次，并且不报错。 */
    @Test
    @DisplayName("会话已过期时仍尽力清理残留文件")
    void stillCleansFilesWhenSessionAlreadyGone() {
        when(valueOperations.get(anyString())).thenReturn(null);

        Result result = service.clear(UPLOAD_ID);

        assertEquals(0, result.getCode());
        verify(minioService).deleteObjectsByPrefix(STAGING);
    }

    /**
     * 孤儿清扫：只删「没有 Redis 会话」的目录，进行中的草稿一个都不能碰。
     *
     * <p>这条断言是防误删的核心：草稿箱里的音频还在用，扫孤儿时若不查 Redis 就会把
     * 正在编辑的草稿文件删掉 —— 比泄漏严重得多。
     */
    @Test
    @DisplayName("孤儿清扫只删无会话目录，保留进行中的草稿")
    @SuppressWarnings("unchecked")
    void cleanupOrphanSkipsLiveSessions() {
        String aliveId = "11111111-1111-4111-8111-111111111111";
        String orphanId = "22222222-2222-4222-8222-222222222222";
        when(minioService.listObjectNames("staging/song-upload/"))
                .thenReturn(List.of(
                        "staging/song-upload/" + aliveId + "/audio.mp3",
                        "staging/song-upload/" + orphanId + "/audio.flac"));
        StringRedisTemplate redisTemplate =
                (StringRedisTemplate) ReflectionTestUtils.getField(service, "stringRedisTemplate");
        when(redisTemplate.hasKey("song-upload:session:" + aliveId)).thenReturn(true);
        when(redisTemplate.hasKey("song-upload:session:" + orphanId)).thenReturn(false);

        int cleaned = service.cleanupOrphanStaging();

        assertEquals(1, cleaned);
        verify(minioService).deleteObjectsByPrefix("staging/song-upload/" + orphanId);
        verify(minioService, never())
                .deleteObjectsByPrefix("staging/song-upload/" + aliveId);
    }

    private static String sessionJson() {
        return "{"
                + "\"uploadId\":\"" + UPLOAD_ID + "\","
                + "\"createdAt\":1,"
                + "\"lastActivityAt\":2,"
                + "\"audioObjectName\":\"" + STAGING + "/audio.mp3\","
                + "\"audioSize\":1024,"
                + "\"lyricsObjectName\":\"" + STAGING + "/lyrics.lrc\""
                + "}";
    }
}
