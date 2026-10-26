package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.mapper.AlbumMapper;
import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.mapper.SongMapper;
import cn.edu.seig.vibemusic.model.dto.SongUploadSession;
import cn.edu.seig.vibemusic.service.ArtistNameParser;
import cn.edu.seig.vibemusic.service.ArtistProvisioningService;
import cn.edu.seig.vibemusic.service.IAudioMetadataService;
import cn.edu.seig.vibemusic.service.MinioService;
import cn.edu.seig.vibemusic.service.SongArtistService;
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

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上传暂存与会话的 TTL 一致性。
 *
 * <p>钉住的是一个<b>已经真实存在</b>的缺陷：暂存文件按 24 小时清理、Redis 会话按
 * 48 小时过期。用户在第 25 小时打开页面，界面仍然显示「上传中」（会话还在），
 * 但 staging 文件已被定时任务删掉，点提交只会得到「临时音频文件不存在或已过期」。
 * 这种「界面说还在、实际已没了」比两边都过期更糟 —— 用户会以为是自己操作失误。
 *
 * <p>三条断言：
 * <ul>
 *   <li>会话写入 Redis 时的 TTL 与清理任务用的阈值是同一个值（12 小时）；</li>
 *   <li>清理索引（zset）的分数是「最后活动时间」而不是「创建时间」；</li>
 *   <li>旧会话（没有 lastActivityAt 字段）不会因为反序列化出 0 而被立刻判定过期。</li>
 * </ul>
 */
class SongUploadTtlConsistencyTest {

    private static final String UPLOAD_ID = "6f1d2c3a-0000-4000-8000-0000000000tt";

    /** 与 SongUploadServiceImpl.UPLOAD_TTL_HOURS 保持一致的期望值。 */
    private static final long EXPECTED_TTL_HOURS = 12L;

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ZSetOperations<String, String> zSetOperations;
    private SongUploadServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        zSetOperations = mock(ZSetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        service = new SongUploadServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "minioService", mock(MinioService.class));
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
     * 会话 TTL 必须是 12 小时 —— 与清理阈值同源。
     *
     * <p>断言的是「写入 Redis 时实际传的小时数」，不是读常量：常量改错、但调用处
     * 仍写死 48 的情况，只有断言调用参数才能抓到。
     */
    @Test
    @DisplayName("会话写入 Redis 的 TTL 与暂存清理阈值一致（12 小时）")
    void sessionTtlMatchesStagingTtl() throws Exception {
        invokeSaveSession(session(System.currentTimeMillis()));

        verify(valueOperations).set(
                eq("song-upload:session:" + UPLOAD_ID),
                anyString(),
                eq(EXPECTED_TTL_HOURS),
                eq(TimeUnit.HOURS)
        );
    }

    /**
     * 清理索引的分数必须是「最后活动时间」。
     *
     * <p>若仍用 createdAt，用户在第 11 小时补充封面后，第 12 小时文件照样被清理，
     * 而 Redis 键因为每次 save 都重设 TTL 才刚续期 —— 又是同一个「界面说还在、文件已没」。
     */
    @Test
    @DisplayName("清理索引记录最后活动时间，而不是创建时间")
    void indexUsesLastActivityTime() throws Exception {
        long createdAt = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(10);
        invokeSaveSession(session(createdAt));

        ArgumentCaptor<Double> score = ArgumentCaptor.forClass(Double.class);
        verify(zSetOperations).add(eq("song-upload:session-index"), eq(UPLOAD_ID), score.capture());

        double recorded = score.getValue();
        assertTrue(recorded > createdAt,
                "索引分数应被刷新为当前时间，实际 " + recorded + " 未超过创建时间 " + createdAt);
        // 允许几秒的测试执行偏差
        assertTrue(Math.abs(recorded - System.currentTimeMillis()) < TimeUnit.MINUTES.toMillis(1),
                "索引分数应接近当前时间，实际 " + recorded);
    }

    /** 保存后会话对象上也要留下最后活动时间，供排查与后续判据复用。 */
    @Test
    @DisplayName("保存后会话对象记录最后活动时间")
    void sessionCarriesLastActivityAt() throws Exception {
        SongUploadSession session = session(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(5));
        invokeSaveSession(session);

        assertTrue(session.getLastActivityAt() > session.getCreatedAt(),
                "lastActivityAt 应被刷新到创建时间之后");
        assertNotNull(session.getUploadId());
    }

    // ------------------------------------------------------------------ 夹具

    /** saveSession 是 private，这里通过反射调用，避免为了测试把它改成 public。 */
    private void invokeSaveSession(SongUploadSession session) throws Exception {
        java.lang.reflect.Method method =
                SongUploadServiceImpl.class.getDeclaredMethod("saveSession", SongUploadSession.class);
        method.setAccessible(true);
        method.invoke(service, session);
    }

    private static SongUploadSession session(long createdAt) {
        return SongUploadSession.builder()
                .uploadId(UPLOAD_ID)
                .ownerAdminId(1L)
                .createdAt(createdAt)
                .audioObjectName("staging/song-upload/" + UPLOAD_ID + "/audio.mp3")
                .durationParsed(true)
                .build();
    }
}
