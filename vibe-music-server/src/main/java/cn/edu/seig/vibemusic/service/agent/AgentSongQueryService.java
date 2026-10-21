package cn.edu.seig.vibemusic.service.agent;

import cn.edu.seig.vibemusic.mapper.AgentSongMapper;
import cn.edu.seig.vibemusic.mapper.PlayHistoryMapper;
import cn.edu.seig.vibemusic.model.agent.SongRef;
import cn.edu.seig.vibemusic.model.vo.AgentSongVO;
import cn.edu.seig.vibemusic.model.vo.ArtistNameVO;
import cn.edu.seig.vibemusic.model.vo.PlayHistoryVO;
import cn.edu.seig.vibemusic.service.SongArtistService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Agent 工具与真实歌曲数据之间的适配层。
 *
 * <p>职责单一：把数据库投影转成 {@link SongRef}。工具层只依赖本类，
 * 因此既不会污染传统 Service 的返回类型，也便于在评测中替换数据来源。
 *
 * <p><b>歌手关系：</b>{@code artistName} 来自主表冗余列（一次查询拿到），
 * {@code artists} 由 {@link SongArtistService} 再批量查一次关系表装配 ——
 * 两次往返、零 JOIN，因此分页 COUNT 与去重都不受影响。
 *
 * <p><b>歌手筛选一律先解析成 id 集合：</b>调用方传的是歌手名（String），
 * 本类负责用 {@link AgentArtistResolver} 把它解析成启用的 {@code artistId} 集合，
 * 再交给 mapper 用 {@code EXISTS} 查权威关系表。因此「给了歌手名但一位都没解析到」
 * 与「没给歌手名」必须区分：前者返回空结果，后者才是不加条件。
 */
@Service
@RequiredArgsConstructor
public class AgentSongQueryService {

    /** 单次工具调用最多返回的歌曲数，避免把过多内容塞进模型上下文。 */
    public static final int MAX_RESULTS = 20;

    private final AgentSongMapper songs;
    private final SongArtistService songArtists;
    private final AgentArtistResolver artistResolver;
    /**
     * 播放历史明细（Agent 的「我最近听了什么」工具）。
     *
     * <p>刻意直接依赖 mapper 而不是 {@code PlayHistoryService}：后者的每个方法都走
     * {@code requireUserId()}（ThreadLocal），而 Agent 的流式请求在独立线程里跑，
     * 身份只存在于 {@link cn.edu.seig.vibemusic.model.agent.AgentContext}。
     * 这里由工具层把可信的 userId 传进来，语义与写操作确认时一致。
     */
    private final PlayHistoryMapper playHistories;

    public record SongPage(List<SongRef> songs, long total, int page, int size) {}

    public SongPage page(String keyword, String artistName, String style, int page, int size) {
        keyword = blankToNull(keyword);
        artistName = blankToNull(artistName);
        style = blankToNull(style);
        if (keyword == null && artistName == null && style == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "请提供搜索条件");
        }
        if (page < 1 || page > 100000 || size < 1 || size > MAX_RESULTS) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "分页参数无效");
        }
        List<Long> artistIds = resolveArtistFilter(artistName);
        if (artistIds != null && artistIds.isEmpty()) {
            // 明确给了歌手名却解析不到启用歌手：返回空页，而不是把空集合当成「不限制」。
            return new SongPage(List.of(), 0, page, size);
        }
        var result = songs.searchPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size),
                keyword, artistIds, style);
        return new SongPage(toRefs(result.getRecords()), result.getTotal(), page, size);
    }

    public List<SongRef> search(String songName, String artistName, String album, String style, int limit) {
        List<Long> artistIds = resolveArtistFilter(artistName);
        if (artistIds != null && artistIds.isEmpty()) {
            return List.of();
        }
        return toRefs(songs.findSongs(blankToNull(songName), artistIds,
                blankToNull(album), blankToNull(style), clamp(limit)));
    }

    /**
     * 按歌手归属精确检索该歌手演唱的歌曲（含合作演唱）。
     *
     * <p>与 {@link #searchByKeyword} 的区别：这里不是关键词召回，而是「这位歌手是否演唱了这首歌」，
     * 因此先把歌手名解析成 {@code artistId} 集合，再由 {@code EXISTS} 查关系表判定归属。
     */
    public List<SongRef> searchByArtistName(String artistName, int limit) {
        List<Long> artistIds = artistResolver.resolveActiveArtistIds(artistName);
        if (artistIds.isEmpty()) {
            return List.of();
        }
        return toRefs(songs.findSongsByArtistIds(artistIds, null, clamp(limit)));
    }

    /** 宽泛关键词检索：歌名/歌手/专辑/风格之间取 OR。 */
    public List<SongRef> searchByKeyword(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) return List.of();
        return toRefs(songs.findSongsByKeyword(keyword.trim(), clamp(limit)));
    }

    /**
     * 随机推荐，应用用户长期偏好里的结构化排除约束。
     *
     * <p>「随便来点歌」是唯一没有用户显式条件的推荐路径，因此也是最需要落排除规则的地方：
     * 排除在 SQL 层执行，保证过滤后仍能取满 {@code limit}（不会出现「排掉几首就只剩零星结果」）。
     */
    public List<SongRef> random(int limit, UserMemoryPreferenceService.ExclusionConstraints exclusions) {
        UserMemoryPreferenceService.ExclusionConstraints safe = safe(exclusions);
        return toRefs(songs.findRandomSongs(clamp(limit),
                List.copyOf(safe.excludedArtistIds()), List.copyOf(safe.excludedStyleNames())));
    }

    /**
     * 歌单候选检索：语义与 {@link #searchByKeyword} 的宽泛关键词一致，
     * 额外支持歌手/风格收窄，并在 <b>SQL 层</b>排除 {@code excludeIds} 中的歌曲
     * 以及用户长期偏好里的结构化排除约束。
     *
     * <p>排除必须发生在 {@code LIMIT} 之前（见 {@code AgentSongMapper#findSongsExcluding}），
     * 否则「曲库里还有大量未使用歌曲」会被误判成「没有新歌」。
     *
     * @param excludeIds 需要排除的歌曲 ID（通常是本会话近期歌单已用过的）；为空表示不排除
     * @param exclusions 用户长期偏好里的结构化排除（歌手 id / 风格名）；为 null 表示不排除
     */
    public List<SongRef> searchExcluding(String keyword, String artistName, String style,
                                         Collection<Long> excludeIds,
                                         UserMemoryPreferenceService.ExclusionConstraints exclusions,
                                         int limit) {
        List<Long> excluded = excludeIds == null ? List.of()
                : excludeIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        UserMemoryPreferenceService.ExclusionConstraints safe = safe(exclusions);
        List<Long> artistIds = resolveArtistFilter(artistName);
        if (artistIds != null && artistIds.isEmpty()) {
            return List.of();
        }
        return toRefs(songs.findSongsExcluding(blankToNull(keyword), artistIds, blankToNull(style),
                excluded, List.copyOf(safe.excludedArtistIds()), List.copyOf(safe.excludedStyleNames()),
                clamp(limit)));
    }

    private static UserMemoryPreferenceService.ExclusionConstraints safe(
            UserMemoryPreferenceService.ExclusionConstraints exclusions) {
        return exclusions == null ? UserMemoryPreferenceService.ExclusionConstraints.none() : exclusions;
    }

    public List<SongRef> byIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return toRefs(songs.findSongsByIds(ids.stream().filter(java.util.Objects::nonNull).distinct().toList()));
    }

    /**
     * 当前用户最近播放的歌曲（按最近一次有效播放时间倒序）。
     *
     * <p><b>为什么必须有这个方法：</b>画像里的播放信号只有计数与歌手名
     * （{@code playedSongCount} / {@code recentPlayArtists}），模型拿不到具体歌名，
     * 被问到「我最近听了什么」时只能凭空描述系统能力 —— 实测会编出
     * 「系统仅记录播放行为、不提供歌名」这种不实回答。歌名本来就在
     * {@code tb_play_history ⋈ tb_song} 里，缺的是把它交给模型的只读工具。
     *
     * <p><b>身份边界：</b>{@code userId} 由工具层从可信的 AgentContext 传入，
     * 模型参数里没有任何用户标识；为 null（游客）时返回空列表，由工具层转成 UNAUTHORIZED，
     * 而不是回落到「全部用户的历史」。
     *
     * <p><b>不外泄播放进度：</b>只映射列表展示需要的字段，
     * {@code last_position_seconds} 不进 {@link SongRef}。
     */
    public List<SongRef> recentlyPlayed(Long userId, int limit) {
        if (userId == null) return List.of();
        List<PlayHistoryVO> rows = playHistories.recentByUser(userId, clamp(limit));
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Map<Long, List<ArtistNameVO>> grouped = songArtists.findArtistsBySongIds(
                rows.stream().map(PlayHistoryVO::getSongId).toList());
        return rows.stream()
                .map(row -> new SongRef(row.getSongId(), row.getSongName(), row.getArtistName(), row.getAlbum(),
                        null, row.getDuration(), row.getCoverUrl(), row.getAudioUrl(),
                        grouped.getOrDefault(row.getSongId(), List.of())))
                .toList();
    }

    /** 把一批投影转成 {@link SongRef}，顺带一次性装配每首歌的结构化歌手列表。 */
    private List<SongRef> toRefs(List<AgentSongVO> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Map<Long, List<ArtistNameVO>> grouped = songArtists.findArtistsBySongIds(
                rows.stream().map(AgentSongVO::getSongId).toList());
        return rows.stream()
                .map(row -> toRef(row, grouped.getOrDefault(row.getSongId(), List.of())))
                .toList();
    }

    private static SongRef toRef(AgentSongVO row, List<ArtistNameVO> artists) {
        return new SongRef(row.getSongId(), row.getSongName(), row.getArtistName(), row.getAlbum(),
                row.getStyle(), row.getDuration(), row.getCoverUrl(), row.getAudioUrl(), artists);
    }

    private static int clamp(int limit) {
        if (limit <= 0) return 10;
        return Math.min(limit, MAX_RESULTS);
    }

    /**
     * 把歌手名解析成可用于 {@code EXISTS} 的 id 集合。
     *
     * @return {@code null} 表示「没给歌手名」（不加歌手条件）；空列表表示「给了但解析不到启用歌手」
     *         —— 两者语义不同，调用方必须区别对待，否则「不存在的歌手」会退化成「全部歌曲」
     */
    private List<Long> resolveArtistFilter(String artistName) {
        if (artistName == null || artistName.isBlank()) {
            return null;
        }
        return artistResolver.resolveActiveArtistIds(artistName);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
