package cn.edu.seig.vibemusic.service;

import cn.edu.seig.vibemusic.constant.MediaCleanupSourceType;
import cn.edu.seig.vibemusic.mapper.MediaCleanupTaskMapper;
import cn.edu.seig.vibemusic.model.entity.MediaCleanupTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 媒体清理 worker 的生命周期验收。
 *
 * <p>这份测试钉住的是三件曾经出错的事：
 * <ol>
 *     <li><b>有引用绝不能删</b>：核对到引用 &gt; 0 必须落 RETAINED，并且一次 MinIO 删除都不发生；</li>
 *     <li><b>失败必须可重试且不能无限重试</b>：失败落 RETRY_WAIT 并退避，达到上限落 FAILED；</li>
 *     <li><b>删除粒度只能是单个对象</b>：绝不允许前缀/通配符删除混进清理路径。</li>
 * </ol>
 *
 * <p>并发语义（领取原子性、租约、代次）依赖真实 SQL 条件更新，由 {@code MediaCleanupSqlConcurrencyTest}
 * 在内存库里验证；本类用 Mockito 隔离状态机本身。
 */
class MediaCleanupTaskTest {

    private static final String BUCKET = "vibe-music-data";

    private MediaCleanupTaskMapper taskMapper;
    private MediaReferenceService referenceService;
    private MinioService minioService;
    private MediaCleanupService service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(MediaCleanupTaskMapper.class);
        referenceService = mock(MediaReferenceService.class);
        minioService = mock(MinioService.class);
        service = new MediaCleanupService(taskMapper, referenceService, minioService, BUCKET);
    }

    private MediaCleanupTask row(long id, String objectKey, int attempts) {
        MediaCleanupTask task = new MediaCleanupTask();
        task.setId(id);
        task.setBucket(BUCKET);
        task.setObjectKey(objectKey);
        task.setSourceType(MediaCleanupSourceType.SONG_DELETE);
        task.setSourceRef("5");
        task.setStatus(MediaCleanupTask.STATUS_RUNNING);
        task.setAttempts(attempts);
        task.setGeneration(7L);
        return task;
    }

    private void claimed(long id, MediaCleanupTask row) {
        when(taskMapper.findDueIds(any(LocalDateTime.class), any(Integer.class))).thenReturn(List.of(id));
        when(taskMapper.claim(eq(id), anyString(), any(), any())).thenReturn(1);
        when(taskMapper.findById(id)).thenReturn(row);
    }

    @Test
    @DisplayName("核对到引用 > 0：落 RETAINED，一次 MinIO 删除都不发生")
    void retainsObjectWhenStillReferenced() {
        MediaCleanupTask row = row(1L, "songCovers/shared.jpg", 1);
        claimed(1L, row);
        when(referenceService.countReferences("songCovers/shared.jpg")).thenReturn(3L);

        int processed = service.runDueTasks();

        assertEquals(1, processed);
        verify(taskMapper).markRetained(eq(1L), anyString(), eq(7L), any());
        verify(minioService, never()).deleteObject(anyString());
        verify(taskMapper, never()).markSucceeded(anyLong(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("引用 = 0：删除成功落 SUCCEEDED，且只传一个完整对象键")
    void deletesUnreferencedObjectWithSingleKey() {
        MediaCleanupTask row = row(2L, "songs/uuid-One Two.mp3", 1);
        claimed(2L, row);
        when(referenceService.countReferences("songs/uuid-One Two.mp3")).thenReturn(0L);

        service.runDueTasks();

        verify(minioService).deleteObject("songs/uuid-One Two.mp3");
        verify(taskMapper).markSucceeded(eq(2L), anyString(), eq(7L), any());
        verify(minioService, never()).deleteObjectsByPrefix(anyString());
    }

    @Test
    @DisplayName("对象本来就不存在（S3 DELETE 幂等）：同样视为删除成功")
    void treatsMissingObjectAsSuccess() {
        MediaCleanupTask row = row(3L, "songs/gone.mp3", 2);
        claimed(3L, row);
        when(referenceService.countReferences("songs/gone.mp3")).thenReturn(0L);
        // deleteObject 对不存在的 key 正常返回、不抛异常

        service.runDueTasks();

        verify(taskMapper).markSucceeded(eq(3L), anyString(), eq(7L), any());
    }

    @Test
    @DisplayName("MinIO 抛异常且未达上限：落 RETRY_WAIT，退避时间在未来且按指数封顶")
    void retriesWithBackoffWhenDeletionFails() {
        MediaCleanupTask row = row(4L, "songs/a.mp3", 1);
        claimed(4L, row);
        when(referenceService.countReferences("songs/a.mp3")).thenReturn(0L);
        doThrow(new RuntimeException("minio timeout")).when(minioService).deleteObject("songs/a.mp3");

        LocalDateTime before = LocalDateTime.now();
        service.runDueTasks();
        LocalDateTime after = LocalDateTime.now();

        ArgumentCaptor<LocalDateTime> nextRun = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(taskMapper).markRetryWait(eq(4L), anyString(), eq(7L), nextRun.capture(), eq("minio timeout"), any());
        // attempts=1 → 退避 2 分钟；窗口放宽到 [before+1min, after+3min)，既排除"没退避"也排除"封顶"
        assertTrue(!nextRun.getValue().isBefore(before.plusMinutes(1)), "退避时间应明显晚于现在");
        assertTrue(nextRun.getValue().isBefore(after.plusMinutes(3)), "退避时间不应超过指数计算结果太多");
        verify(taskMapper, never()).markFailed(anyLong(), anyString(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("达到尝试上限仍失败：落终态 FAILED，不再安排重试")
    void failsPermanentlyAtAttemptLimit() {
        MediaCleanupTask row = row(5L, "songs/b.mp3", MediaCleanupService.MAX_ATTEMPTS);
        claimed(5L, row);
        when(referenceService.countReferences("songs/b.mp3")).thenReturn(0L);
        doThrow(new RuntimeException("minio down")).when(minioService).deleteObject("songs/b.mp3");

        service.runDueTasks();

        verify(taskMapper).markFailed(eq(5L), anyString(), eq(7L), eq("minio down"), any());
        verify(taskMapper, never()).markRetryWait(anyLong(), anyString(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("任务桶与配置不一致：拒绝猜测性删除，直接落 FAILED 转人工")
    void refusesToDeleteObjectFromAnotherBucket() {
        MediaCleanupTask row = row(6L, "songs/c.mp3", 1);
        row.setBucket("some-other-bucket");
        claimed(6L, row);

        service.runDueTasks();

        verify(taskMapper).markFailed(eq(6L), anyString(), eq(7L), anyString(), any());
        verify(minioService, never()).deleteObject(anyString());
        verify(referenceService, never()).countReferences(anyString());
    }

    @Test
    @DisplayName("领取失败（被其他执行者抢先或状态已变）：放弃该任务且不做任何回写")
    void skipsTaskLostToAnotherClaimer() {
        when(taskMapper.claim(eq(7L), anyString(), any(), any())).thenReturn(0);

        service.runDueTasks();

        verify(taskMapper, never()).findById(anyLong());
        verify(minioService, never()).deleteObject(anyString());
        verify(taskMapper, never()).markSucceeded(anyLong(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("登记：URL 被解析成对象键、桶与摘要一起落库；空白 URL 不登记")
    void enqueuesResolvedObjectKeyAndSkipsBlankUrl() {
        when(minioService.toObjectName("http://127.0.0.1:9005/vibe-music-data/songs/u-One Two.mp3"))
                .thenReturn("songs/u-One Two.mp3");

        service.enqueue("http://127.0.0.1:9005/vibe-music-data/songs/u-One Two.mp3",
                MediaCleanupSourceType.SONG_DELETE, "5", "删除歌曲音频");
        service.enqueue("  ", MediaCleanupSourceType.SONG_DELETE, "6", "空 URL");
        service.enqueueForId(null, MediaCleanupSourceType.SONG_DELETE, 7L, "null URL");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> digest = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).enqueue(eq(BUCKET), key.capture(), digest.capture(),
                eq(MediaCleanupSourceType.SONG_DELETE), eq("5"), eq("删除歌曲音频"), any(), any());
        assertEquals("songs/u-One Two.mp3", key.getValue());
        assertNotNull(digest.getValue());
        assertEquals(64, digest.getValue().length(), "key_digest 必须是 SHA-256 的 64 位十六进制");
        assertEquals(MediaCleanupService.sha256Hex("songs/u-One Two.mp3"), digest.getValue());
        verify(taskMapper, never()).enqueue(eq(BUCKET), eq(""), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("退避曲线：指数增长并在 30 分钟封顶，防止移位溢出")
    void backoffGrowsExponentiallyAndCapsAtThirtyMinutes() {
        assertEquals(1, MediaCleanupService.backoffMinutes(0));
        assertEquals(2, MediaCleanupService.backoffMinutes(1));
        assertEquals(4, MediaCleanupService.backoffMinutes(2));
        assertEquals(8, MediaCleanupService.backoffMinutes(3));
        assertEquals(16, MediaCleanupService.backoffMinutes(4));
        assertEquals(30, MediaCleanupService.backoffMinutes(5));
        assertEquals(30, MediaCleanupService.backoffMinutes(20));
    }

    @Test
    @DisplayName("删除粒度约束：整轮处理里绝不允许出现前缀/多对象删除")
    void neverUsesPrefixDeletionAcrossWholeRun() {
        MediaCleanupTask row = row(8L, "songs/d.mp3", 1);
        claimed(8L, row);
        when(referenceService.countReferences("songs/d.mp3")).thenReturn(0L);
        doThrow(new RuntimeException("boom")).when(minioService).deleteObject("songs/d.mp3");

        service.runDueTasks();

        verify(minioService, never()).deleteObjectsByPrefix(anyString());
    }

    @Test
    @DisplayName("同一来源 id 的两个不同对象（封面与音频）登记为两条任务，互不覆盖")
    void enqueuesCoverAndAudioAsTwoIndependentTasks() {
        when(minioService.toObjectName("http://x/cover.jpg")).thenReturn("cover.jpg");
        when(minioService.toObjectName("http://x/audio.mp3")).thenReturn("audio.mp3");

        service.enqueueForId("http://x/cover.jpg", MediaCleanupSourceType.SONG_DELETE, 5L, "封面");
        service.enqueueForId("http://x/audio.mp3", MediaCleanupSourceType.SONG_DELETE, 5L, "音频");

        verify(taskMapper).enqueue(eq(BUCKET), eq("cover.jpg"), any(), any(), eq("5"), eq("封面"), any(), any());
        verify(taskMapper).enqueue(eq(BUCKET), eq("audio.mp3"), any(), any(), eq("5"), eq("音频"), any(), any());
    }

    @Test
    @DisplayName("待清理任务列表：返回的处理数等于领取成功的任务数")
    void reportsOnlyClaimedTasksAsProcessed() {
        MediaCleanupTask row = row(9L, "songs/e.mp3", 1);
        claimed(9L, row);
        when(taskMapper.claim(eq(10L), anyString(), any(), any())).thenReturn(0);
        when(taskMapper.findDueIds(any(LocalDateTime.class), any(Integer.class)))
                .thenReturn(List.of(9L, 10L));
        when(referenceService.countReferences("songs/e.mp3")).thenReturn(0L);

        int processed = service.runDueTasks();

        assertEquals(1, processed);
        verify(minioService).deleteObject("songs/e.mp3");
    }
}
