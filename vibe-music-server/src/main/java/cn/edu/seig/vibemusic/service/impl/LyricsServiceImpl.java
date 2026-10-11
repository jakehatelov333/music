package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.config.LyricsProperties;
import cn.edu.seig.vibemusic.enumeration.LyricStatusEnum;
import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.mapper.SongMapper;
import cn.edu.seig.vibemusic.model.dto.LyricsBatchRequestDTO;
import cn.edu.seig.vibemusic.model.dto.LyricsSaveDTO;
import cn.edu.seig.vibemusic.model.entity.Artist;
import cn.edu.seig.vibemusic.model.entity.Song;
import cn.edu.seig.vibemusic.model.vo.LyricsBatchTaskVO;
import cn.edu.seig.vibemusic.model.vo.LyricsFetchVO;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.ILyricsService;
import cn.edu.seig.vibemusic.service.lyrics.LyricsCandidate;
import cn.edu.seig.vibemusic.service.lyrics.LyricsMatcher;
import cn.edu.seig.vibemusic.service.lyrics.LyricsProvider;
import cn.edu.seig.vibemusic.service.lyrics.LyricsTextCleaner;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 歌词补全服务实现。
 * <p>
 * 核心链路：构造查询 → 依次尝试各数据源（QQ音乐优先，LRCLIB 兜底）→ 匹配打分 →
 * 清洗并重组为 QQ 音乐风格 LRC → 落库。
 * <p>
 * 两条重要规则：
 * <ul>
 *   <li><b>已有歌词默认不被覆盖</b>，必须显式传入 {@code force=true}</li>
 *   <li>所有数据源都无匹配时写入 {@code lyric_status=3}（负缓存），便于管理端区分"无匹配"与"未获取"</li>
 * </ul>
 */
@Slf4j
@Service
public class LyricsServiceImpl implements ILyricsService {

    private static final String TASK_KEY_PREFIX = "lyrics:task:";
    private static final String CANCEL_KEY_PREFIX = "lyrics:task:cancel:";
    /** 批量任务的执行位，保证同一时刻只有一个批量任务在跑 */
    private static final String RUNNING_KEY = "lyrics:batch:running";
    /** 协调本实例内的取消请求与任务终态写入，避免结束/取消状态互相覆盖 */
    private final Object taskLifecycleLock = new Object();
    private static final int MAX_FAILURE_DETAIL = 20;
    /** 任务心跳超过 3 分钟即视为僵尸任务 */
    private static final long STALE_TASK_MILLIS = 3 * 60 * 1000L;
    private static final String SONG_CACHE = "songCache";

    @Autowired
    private SongMapper songMapper;
    @Autowired
    private ArtistMapper artistMapper;
    @Autowired
    private List<LyricsProvider> providers;
    @Autowired
    private LyricsMatcher matcher;
    @Autowired
    private LyricsTextCleaner cleaner;
    @Autowired
    private LyricsProperties properties;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private CacheManager cacheManager;
    @Autowired
    @Qualifier("lyricsTaskExecutor")
    private ThreadPoolTaskExecutor taskExecutor;

    /** 单次抓取的结果 */
    private record Fetched(boolean matched, String lyric, String source, Double score, String message) {
    }

    // ------------------------------------------------------------------ 单曲

    @Override
    public Result<LyricsFetchVO> preview(Long songId) {
        if (!properties.isEnabled()) {
            return Result.error("歌词补全功能当前已关闭");
        }
        Song song = songMapper.selectById(songId);
        if (song == null) {
            return Result.error("歌曲不存在");
        }

        String artistName = resolveArtistName(song, null);
        Fetched fetched = fetchFromProviders(song, artistName, null, null);

        LyricsFetchVO vo = buildVO(song, artistName, fetched);
        if (!fetched.matched()) {
            return Result.success("未找到匹配的歌词", vo);
        }
        return Result.success("匹配成功", vo);
    }

    @Override
    @CacheEvict(cacheNames = SONG_CACHE, allEntries = true)
    public Result<LyricsFetchVO> save(Long songId, LyricsSaveDTO dto) {
        Song song = songMapper.selectById(songId);
        if (song == null) {
            return Result.error("歌曲不存在");
        }

        boolean hasExisting = StringUtils.hasText(song.getLyric());
        boolean force = dto != null && Boolean.TRUE.equals(dto.getForce());
        if (hasExisting && !force) {
            return Result.error("该歌曲已有歌词，如需覆盖请勾选「强制覆盖」");
        }

        String lyric = dto == null || dto.getLyric() == null ? null : dto.getLyric().strip();
        String artistName = resolveArtistName(song, null);

        UpdateWrapper<Song> update = new UpdateWrapper<Song>().eq("id", songId);
        if (StringUtils.hasText(lyric)) {
            update.set("lyric", lyric)
                    .set("lyric_source", "MANUAL")
                    .set("lyric_status", LyricStatusEnum.MANUAL.getId())
                    .set("lyric_updated_at", LocalDateTime.now());
        } else {
            // 清空歌词
            update.set("lyric", null)
                    .set("lyric_source", null)
                    .set("lyric_status", LyricStatusEnum.MISSING.getId())
                    .set("lyric_updated_at", LocalDateTime.now());
        }

        if (songMapper.update(null, update) != 1) {
            return Result.error("歌词保存失败，请重试");
        }

        LyricsFetchVO vo = new LyricsFetchVO();
        vo.setSongId(songId);
        vo.setSongName(song.getSongName());
        vo.setArtistName(artistName);
        vo.setAlbum(resolveAlbumName(song));
        vo.setMatched(StringUtils.hasText(lyric));
        vo.setSource("MANUAL");
        vo.setLyric(lyric);
        vo.setHasExistingLyric(StringUtils.hasText(lyric));
        vo.setMessage(StringUtils.hasText(lyric) ? "歌词已保存" : "歌词已清空");
        return Result.success(vo.getMessage(), vo);
    }

    // ------------------------------------------------------------------ 批量

    @Override
    public Result<LyricsBatchTaskVO> submitBatch(LyricsBatchRequestDTO dto) {
        if (!properties.isEnabled()) {
            return Result.error("歌词补全功能当前已关闭");
        }
        LyricsBatchRequestDTO request = dto == null ? new LyricsBatchRequestDTO() : dto;

        // 同一时刻只允许一个批量任务，否则重复点击会把任务堆在队列里长时间不动
        LyricsBatchTaskVO running = currentTask();
        if (running != null) {
            return Result.error("已有批量补全任务正在执行（" + running.getDone() + "/" + running.getTotal()
                    + "），请等待完成后再提交");
        }

        List<Long> targetIds = resolveTargetSongIds(request);
        if (targetIds.isEmpty()) {
            return Result.error(noTargetMessage(request));
        }

        boolean force = Boolean.TRUE.equals(request.getForce());
        String taskId = UUID.randomUUID().toString();
        if (!claimBatchSlot(taskId)) {
            return Result.error("已有批量补全任务正在执行，请等待完成后再提交");
        }

        LyricsBatchTaskVO task = new LyricsBatchTaskVO();
        task.setTaskId(taskId);
        task.setStatus("RUNNING");
        task.setTitle(buildTaskTitle(request));
        task.setTotal(targetIds.size());
        task.setDone(0);
        task.setSuccess(0);
        task.setNoMatch(0);
        task.setSkipped(0);
        task.setFailed(0);
        task.setStartedAt(System.currentTimeMillis());
        saveTask(task);

        log.info("提交歌词批量补全任务：taskId={}, 歌曲数={}, 强制覆盖={}", taskId, targetIds.size(), force);
        try {
            taskExecutor.execute(() -> runBatch(taskId, targetIds, force));
        } catch (RuntimeException e) {
            releaseBatchSlot(taskId);
            throw e;
        }

        return Result.success("批量补全任务已提交", task);
    }

    @Override
    public Result<LyricsBatchTaskVO> getTask(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return Result.error("任务不存在");
        }
        LyricsBatchTaskVO task = loadTask(taskId);
        if (task == null) {
            return Result.error("任务不存在或已过期");
        }
        return Result.success(task);
    }

    /** 返回当前正在执行的批量任务，没有则返回 null（供管理端刷新页面后恢复进度） */
    @Override
    public Result<LyricsBatchTaskVO> getCurrentTask() {
        return Result.success(currentTask());
    }

    @Override
    public Result<LyricsBatchTaskVO> cancelTask(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return Result.error("任务不存在");
        }

        synchronized (taskLifecycleLock) {
            LyricsBatchTaskVO task = loadTask(taskId);
            if (task == null) {
                return Result.error("任务不存在或已过期");
            }
            if ("DONE".equals(task.getStatus()) || "CANCELLED".equals(task.getStatus())) {
                return Result.success("任务已结束，无需停止", task);
            }
            if (!"RUNNING".equals(task.getStatus()) && !"CANCELLING".equals(task.getStatus())) {
                return Result.error("当前任务状态不支持停止");
            }

            String runningTaskId = stringRedisTemplate.opsForValue().get(RUNNING_KEY);
            if (!taskId.equals(runningTaskId)) {
                return Result.error("该任务当前未运行，无法停止");
            }

            stringRedisTemplate.opsForValue().set(
                    CANCEL_KEY_PREFIX + taskId,
                    "1",
                    Math.max(1, properties.getBatch().getTaskTtlHours()),
                    TimeUnit.HOURS);
            task.setStatus("CANCELLING");
            saveTask(task);
            return Result.success("已请求停止；当前歌曲的数据源请求结束后将停止后续搜索", task);
        }
    }

    @Override
    public Result<Integer> markInstrumental(List<Long> songIds) {
        List<Long> ids = songIds == null
                ? List.of()
                : songIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Result.error("请先选择要标记为伴奏的歌曲");
        }

        int maxSize = Math.max(1, properties.getBatch().getMaxTaskSize());
        if (ids.size() > maxSize) {
            return Result.error("一次最多可批量标记 " + maxSize + " 首歌曲");
        }

        int updated = songMapper.update(null, new UpdateWrapper<Song>()
                .in("id", ids)
                .set("lyric", null)
                .set("lyric_source", "INSTRUMENTAL")
                .set("lyric_status", LyricStatusEnum.INSTRUMENTAL.getId())
                .set("lyric_updated_at", LocalDateTime.now()));
        if (updated <= 0) {
            return Result.error("所选歌曲不存在或标记失败");
        }

        clearSongCache();
        return Result.success("已将 " + updated + " 首歌曲标记为伴奏，并清空现有歌词", updated);
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 依次尝试各数据源，返回首个匹配成功的结果。
     */
    private Fetched fetchFromProviders(Song song, String artistName, Map<Long, String> artistCache, String batchTaskId) {
        String albumName = resolveAlbumName(song);
        LyricsProvider.Query query = new LyricsProvider.Query(
                song.getSongName(),
                artistName,
                albumName,
                parseDuration(song.getDuration())
        );

        for (LyricsProvider provider : orderedProviders()) {
            if (isCancelRequested(batchTaskId)) {
                break;
            }
            if (!provider.enabled()) {
                continue;
            }
            try {
                List<LyricsCandidate> candidates = provider.search(query);
                if (isCancelRequested(batchTaskId)) {
                    break;
                }
                if (candidates.isEmpty()) {
                    continue;
                }
                LyricsMatcher.Scored best = matcher.pickBest(candidates, query);
                if (best == null) {
                    continue;
                }
                String cleaned = cleaner.clean(best.candidate().lyric(), song.getSongName(), artistName, albumName);
                if (!StringUtils.hasText(cleaned)) {
                    continue;
                }
                log.debug("歌曲 {}-{} 命中歌词源 {}，得分 {}", artistName, song.getSongName(),
                        best.candidate().source(), best.score());
                return new Fetched(true, cleaned, best.candidate().source(), best.score(), null);
            } catch (Exception e) {
                log.warn("歌词源 {} 处理失败，songId={}", provider.source(), song.getSongId(), e);
            }
        }
        return new Fetched(false, null, null, null, "所有歌词源均未找到匹配结果");
    }

    private List<LyricsProvider> orderedProviders() {
        return providers.stream()
                .sorted(Comparator.comparingInt(LyricsProvider::order))
                .toList();
    }

    private void runBatch(String taskId, List<Long> songIds, boolean force) {
        try {
            doRunBatch(taskId, songIds, force);
        } finally {
            // 无论成功失败都要释放执行位，否则后续批量任务会被一直挡住
            releaseBatchSlot(taskId);
        }
    }

    private void doRunBatch(String taskId, List<Long> songIds, boolean force) {
        LyricsBatchTaskVO task = loadTask(taskId);
        if (task == null) {
            log.warn("批量任务 {} 状态丢失，终止执行", taskId);
            return;
        }

        Map<Long, String> artistCache = new HashMap<>();
        long delay = Math.max(0, properties.getBatch().getDelayMs());

        for (Long songId : songIds) {
            if (isCancelRequested(taskId)) {
                break;
            }
            try {
                Song song = songMapper.selectById(songId);
                if (song == null) {
                    task.setFailed(task.getFailed() + 1);
                    addFailure(task, songId + " 已不存在");
                } else if (Objects.equals(song.getLyricStatus(), LyricStatusEnum.INSTRUMENTAL.getId())
                        || (StringUtils.hasText(song.getLyric()) && !force)) {
                    task.setSkipped(task.getSkipped() + 1);
                } else {
                    String artistName = resolveArtistName(song, artistCache);
                    Fetched fetched = fetchFromProviders(song, artistName, artistCache, taskId);
                    if (isCancelRequested(taskId)) {
                        break;
                    }
                    if (fetched.matched()) {
                        if (persistFetched(song, fetched)) {
                            task.setSuccess(task.getSuccess() + 1);
                        } else {
                            task.setSkipped(task.getSkipped() + 1);
                        }
                    } else if (markNoMatch(song)) {
                        task.setNoMatch(task.getNoMatch() + 1);
                    } else {
                        task.setSkipped(task.getSkipped() + 1);
                    }
                }
            } catch (Exception e) {
                if (isCancelRequested(taskId)) {
                    break;
                }
                task.setFailed(task.getFailed() + 1);
                addFailure(task, songId + " 处理异常：" + e.getMessage());
                log.warn("批量补全歌词失败，songId={}", songId, e);
            }

            if (isCancelRequested(taskId)) {
                break;
            }
            task.setDone(task.getDone() + 1);
            // 每首完成后立即持久化，保证前端进度平滑推进（Redis 写入开销可忽略）
            saveTask(task);

            if (delay > 0 && task.getDone() < task.getTotal() && !waitForBatchDelay(taskId, delay)) {
                break;
            }
        }

        synchronized (taskLifecycleLock) {
            boolean cancelled = isCancelRequested(taskId) || Thread.currentThread().isInterrupted();
            task.setStatus(cancelled ? "CANCELLED" : "DONE");
            task.setFinishedAt(System.currentTimeMillis());
            saveTask(task);
            stringRedisTemplate.delete(CANCEL_KEY_PREFIX + taskId);
        }

        // 批量更新了歌曲或结束了任务，清空歌曲详情缓存
        clearSongCache();

        log.info("歌词批量任务结束：taskId={}, 状态={}, 成功={}, 无匹配={}, 跳过={}, 失败={}",
                taskId, task.getStatus(), task.getSuccess(), task.getNoMatch(), task.getSkipped(), task.getFailed());
    }

    private boolean waitForBatchDelay(String taskId, long delayMs) {
        long deadline = System.currentTimeMillis() + delayMs;
        while (System.currentTimeMillis() < deadline) {
            if (isCancelRequested(taskId)) {
                return false;
            }
            long remaining = deadline - System.currentTimeMillis();
            try {
                Thread.sleep(Math.min(100, Math.max(1, remaining)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !isCancelRequested(taskId);
    }

    private boolean persistFetched(Song song, Fetched fetched) {
        return songMapper.update(null, new UpdateWrapper<Song>()
                .eq("id", song.getSongId())
                .ne("lyric_status", LyricStatusEnum.INSTRUMENTAL.getId())
                .set("lyric", fetched.lyric())
                .set("lyric_source", fetched.source())
                .set("lyric_status", LyricStatusEnum.FETCHED.getId())
                .set("lyric_updated_at", LocalDateTime.now())) > 0;
    }

    private boolean markNoMatch(Song song) {
        return songMapper.update(null, new UpdateWrapper<Song>()
                .eq("id", song.getSongId())
                .ne("lyric_status", LyricStatusEnum.INSTRUMENTAL.getId())
                .set("lyric_status", LyricStatusEnum.NO_MATCH.getId())
                .set("lyric_updated_at", LocalDateTime.now())) > 0;
    }

    private List<Long> resolveTargetSongIds(LyricsBatchRequestDTO request) {
        int maxSize = Math.max(1, properties.getBatch().getMaxTaskSize());
        int limit = request.getLimit() != null && request.getLimit() > 0
                ? Math.min(request.getLimit(), maxSize) : maxSize;

        // 1. 指定歌曲 id
        if (request.getSongIds() != null && !request.getSongIds().isEmpty()) {
            return request.getSongIds().stream()
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .limit(limit)
                    .toList();
        }

        // 2. 指定专辑
        QueryWrapper<Song> wrapper = new QueryWrapper<>();
        if (request.getAlbumId() != null) {
            wrapper.eq("album_id", request.getAlbumId());
        }
        // 3. 默认只处理缺歌词的歌曲
        if (!Boolean.FALSE.equals(request.getOnlyMissing())) {
            wrapper.and(w -> w.isNull("lyric").or().eq("lyric", ""));
            // 确认过的伴奏始终跳过；无匹配歌曲仅在显式重试时纳入任务。
            if (Boolean.TRUE.equals(request.getRetryNoMatch())) {
                wrapper.ne("lyric_status", LyricStatusEnum.INSTRUMENTAL.getId());
            } else {
                wrapper.notIn("lyric_status", LyricStatusEnum.NO_MATCH.getId(), LyricStatusEnum.INSTRUMENTAL.getId());
            }
        }
        wrapper.orderByAsc("id").last("LIMIT " + limit);

        return songMapper.selectList(wrapper).stream().map(Song::getSongId).toList();
    }

    private String buildTaskTitle(LyricsBatchRequestDTO request) {
        if (request.getAlbumId() != null) {
            return "补全专辑歌词";
        }
        if (request.getSongIds() != null && !request.getSongIds().isEmpty()) {
            return "批量补全所选 " + request.getSongIds().size() + " 首";
        }
        return "批量补全缺失歌词";
    }

    private LyricsFetchVO buildVO(Song song, String artistName, Fetched fetched) {
        LyricsFetchVO vo = new LyricsFetchVO();
        vo.setSongId(song.getSongId());
        vo.setSongName(song.getSongName());
        vo.setArtistName(artistName);
        vo.setAlbum(resolveAlbumName(song));
        vo.setMatched(fetched.matched());
        vo.setSource(fetched.source());
        vo.setScore(fetched.score());
        vo.setLyric(fetched.lyric());
        vo.setMessage(fetched.message());
        vo.setHasExistingLyric(StringUtils.hasText(song.getLyric()));
        return vo;
    }

    private String resolveArtistName(Song song, Map<Long, String> cache) {
        if (song.getArtistId() == null) {
            return null;
        }
        if (cache != null && cache.containsKey(song.getArtistId())) {
            return cache.get(song.getArtistId());
        }
        Artist artist = artistMapper.selectById(song.getArtistId());
        String name = artist == null ? null : artist.getArtistName();
        if (cache != null) {
            cache.put(song.getArtistId(), name);
        }
        return name;
    }

    private String resolveAlbumName(Song song) {
        return StringUtils.hasText(song.getAlbum()) ? song.getAlbum().trim() : null;
    }

    private Double parseDuration(String duration) {
        if (!StringUtils.hasText(duration)) {
            return null;
        }
        try {
            return Double.parseDouble(duration.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void addFailure(LyricsBatchTaskVO task, String detail) {
        if (task.getFailures() == null) {
            task.setFailures(new ArrayList<>());
        }
        if (task.getFailures().size() < MAX_FAILURE_DETAIL) {
            task.getFailures().add(detail);
        }
    }

    private boolean isCancelRequested(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return false;
        }
        try {
            return "1".equals(stringRedisTemplate.opsForValue().get(CANCEL_KEY_PREFIX + taskId));
        } catch (Exception e) {
            log.warn("读取歌词任务停止标记失败，taskId={}", taskId, e);
            return false;
        }
    }

    private void saveTask(LyricsBatchTaskVO task) {
        try {
            if ("RUNNING".equals(task.getStatus()) && isCancelRequested(task.getTaskId())) {
                task.setStatus("CANCELLING");
            }
            task.setHeartbeatAt(System.currentTimeMillis());
            stringRedisTemplate.opsForValue().set(
                    TASK_KEY_PREFIX + task.getTaskId(),
                    objectMapper.writeValueAsString(task),
                    Math.max(1, properties.getBatch().getTaskTtlHours()),
                    TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("保存歌词任务状态失败，taskId={}", task.getTaskId(), e);
        }
    }

    private LyricsBatchTaskVO loadTask(String taskId) {
        try {
            String json = stringRedisTemplate.opsForValue().get(TASK_KEY_PREFIX + taskId);
            if (!StringUtils.hasText(json)) {
                return null;
            }
            return objectMapper.readValue(json, LyricsBatchTaskVO.class);
        } catch (Exception e) {
            log.warn("读取歌词任务状态失败，taskId={}", taskId, e);
            return null;
        }
    }

    /** 当前正在执行的批量任务，没有则返回 null */
    private LyricsBatchTaskVO currentTask() {
        try {
            String taskId = stringRedisTemplate.opsForValue().get(RUNNING_KEY);
            if (!StringUtils.hasText(taskId)) {
                return null;
            }
            LyricsBatchTaskVO task = loadTask(taskId);
            if (task == null
                    || (!"RUNNING".equals(task.getStatus()) && !"CANCELLING".equals(task.getStatus()))
                    || isStale(task)) {
                // 陈旧执行位：任务已结束、状态丢失，或应用重启导致任务线程消失
                releaseBatchSlot(taskId);
                return null;
            }
            return task;
        } catch (Exception e) {
            log.warn("读取当前歌词任务失败", e);
            return null;
        }
    }

    /** 心跳超过该时长即认为任务已死（应用重启会打断任务线程） */
    private boolean isStale(LyricsBatchTaskVO task) {
        Long heartbeat = task.getHeartbeatAt();
        if (heartbeat == null) {
            // 兼容早期没有心跳字段的任务记录
            heartbeat = task.getStartedAt();
        }
        return heartbeat == null || System.currentTimeMillis() - heartbeat > STALE_TASK_MILLIS;
    }

    /** 抢占批量任务执行位，保证同一时刻只有一个批量任务 */
    private boolean claimBatchSlot(String taskId) {
        Boolean ok = stringRedisTemplate.opsForValue().setIfAbsent(
                RUNNING_KEY, taskId,
                Math.max(1, properties.getBatch().getTaskTtlHours()), TimeUnit.HOURS);
        return Boolean.TRUE.equals(ok);
    }

    /** 释放执行位（仅当仍属于自己时，避免误删后续任务占用的位） */
    private void releaseBatchSlot(String taskId) {
        try {
            String current = stringRedisTemplate.opsForValue().get(RUNNING_KEY);
            if (StringUtils.hasText(current) && current.equals(taskId)) {
                stringRedisTemplate.delete(RUNNING_KEY);
            }
        } catch (Exception e) {
            log.warn("释放歌词任务执行位失败，taskId={}", taskId, e);
        }
    }

    /** 没有可处理歌曲时给出更有信息量的提示 */
    private String noTargetMessage(LyricsBatchRequestDTO request) {
        if (!Boolean.TRUE.equals(request.getRetryNoMatch())) {
            Long noMatch = songMapper.selectCount(new QueryWrapper<Song>()
                    .eq("lyric_status", LyricStatusEnum.NO_MATCH.getId()));
            if (noMatch != null && noMatch > 0) {
                return "没有需要补全的歌曲；另有 " + noMatch
                        + " 首已判定为无匹配（多为纯音乐、古典曲），如需重试请使用「重试无匹配」";
            }
        }
        return "没有需要处理的歌曲";
    }

    private void clearSongCache() {
        try {
            Cache cache = cacheManager.getCache(SONG_CACHE);
            if (cache != null) {
                cache.clear();
            }
        } catch (Exception e) {
            log.warn("清理歌曲缓存失败", e);
        }
    }

}
