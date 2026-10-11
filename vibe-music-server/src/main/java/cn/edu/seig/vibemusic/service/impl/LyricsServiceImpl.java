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
    private static final int MAX_FAILURE_DETAIL = 20;
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
        Fetched fetched = fetchFromProviders(song, artistName, null);

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

        List<Long> targetIds = resolveTargetSongIds(request);
        if (targetIds.isEmpty()) {
            return Result.error("没有需要处理的歌曲");
        }

        boolean force = Boolean.TRUE.equals(request.getForce());
        String taskId = UUID.randomUUID().toString();

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
        taskExecutor.execute(() -> runBatch(taskId, targetIds, force));

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

    // ------------------------------------------------------------------ 内部

    /**
     * 依次尝试各数据源，返回首个匹配成功的结果。
     */
    private Fetched fetchFromProviders(Song song, String artistName, Map<Long, String> artistCache) {
        String albumName = resolveAlbumName(song);
        LyricsProvider.Query query = new LyricsProvider.Query(
                song.getSongName(),
                artistName,
                albumName,
                parseDuration(song.getDuration())
        );

        for (LyricsProvider provider : orderedProviders()) {
            if (!provider.enabled()) {
                continue;
            }
            try {
                List<LyricsCandidate> candidates = provider.search(query);
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
        LyricsBatchTaskVO task = loadTask(taskId);
        if (task == null) {
            log.warn("批量任务 {} 状态丢失，终止执行", taskId);
            return;
        }

        Map<Long, String> artistCache = new HashMap<>();
        long delay = Math.max(0, properties.getBatch().getDelayMs());

        for (Long songId : songIds) {
            try {
                Song song = songMapper.selectById(songId);
                if (song == null) {
                    task.setFailed(task.getFailed() + 1);
                    addFailure(task, songId + " 已不存在");
                } else if (StringUtils.hasText(song.getLyric()) && !force) {
                    task.setSkipped(task.getSkipped() + 1);
                } else {
                    String artistName = resolveArtistName(song, artistCache);
                    Fetched fetched = fetchFromProviders(song, artistName, artistCache);
                    if (fetched.matched()) {
                        persistFetched(song, fetched);
                        task.setSuccess(task.getSuccess() + 1);
                    } else {
                        markNoMatch(song);
                        task.setNoMatch(task.getNoMatch() + 1);
                    }
                }
            } catch (Exception e) {
                task.setFailed(task.getFailed() + 1);
                addFailure(task, songId + " 处理异常：" + e.getMessage());
                log.warn("批量补全歌词失败，songId={}", songId, e);
            }

            task.setDone(task.getDone() + 1);
            // 每首完成后立即持久化，保证前端进度平滑推进（Redis 写入开销可忽略）
            saveTask(task);

            if (delay > 0 && task.getDone() < task.getTotal()) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        task.setStatus("DONE");
        task.setFinishedAt(System.currentTimeMillis());
        saveTask(task);

        // 批量更新了大量歌曲，清空歌曲详情缓存
        clearSongCache();

        log.info("歌词批量补全完成：taskId={}, 成功={}, 无匹配={}, 跳过={}, 失败={}",
                taskId, task.getSuccess(), task.getNoMatch(), task.getSkipped(), task.getFailed());
    }

    private void persistFetched(Song song, Fetched fetched) {
        songMapper.update(null, new UpdateWrapper<Song>()
                .eq("id", song.getSongId())
                .set("lyric", fetched.lyric())
                .set("lyric_source", fetched.source())
                .set("lyric_status", LyricStatusEnum.FETCHED.getId())
                .set("lyric_updated_at", LocalDateTime.now()));
    }

    private void markNoMatch(Song song) {
        songMapper.update(null, new UpdateWrapper<Song>()
                .eq("id", song.getSongId())
                .set("lyric_status", LyricStatusEnum.NO_MATCH.getId())
                .set("lyric_updated_at", LocalDateTime.now()));
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

    private void saveTask(LyricsBatchTaskVO task) {
        try {
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
