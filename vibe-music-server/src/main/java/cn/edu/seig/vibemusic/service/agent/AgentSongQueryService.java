package cn.edu.seig.vibemusic.service.agent;

import cn.edu.seig.vibemusic.mapper.AgentSongMapper;
import cn.edu.seig.vibemusic.model.agent.SongRef;
import cn.edu.seig.vibemusic.model.vo.AgentSongVO;
import cn.edu.seig.vibemusic.model.vo.ArtistNameVO;
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

    public List<SongRef> random(int limit) {
        return toRefs(songs.findRandomSongs(clamp(limit)));
    }

    /**
     * 歌单候选检索：语义与 {@link #searchByKeyword} 的宽泛关键词一致，
     * 额外支持歌手/风格收窄，并在 <b>SQL 层</b>排除 {@code excludeIds} 中的歌曲。
     *
     * <p>排除必须发生在 {@code LIMIT} 之前（见 {@code AgentSongMapper#findSongsExcluding}），
     * 否则「曲库里还有大量未使用歌曲」会被误判成「没有新歌」。
     *
     * @param excludeIds 需要排除的歌曲 ID（通常是本会话近期歌单已用过的）；为空表示不排除
     */
    public List<SongRef> searchExcluding(String keyword, String artistName, String style,
                                         Collection<Long> excludeIds, int limit) {
        List<Long> excluded = excludeIds == null ? List.of()
                : excludeIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        List<Long> artistIds = resolveArtistFilter(artistName);
        if (artistIds != null && artistIds.isEmpty()) {
            return List.of();
        }
        return toRefs(songs.findSongsExcluding(blankToNull(keyword), artistIds, blankToNull(style),
                excluded, clamp(limit)));
    }

    public List<SongRef> byIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return toRefs(songs.findSongsByIds(ids.stream().filter(java.util.Objects::nonNull).distinct().toList()));
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
