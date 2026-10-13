package cn.edu.seig.vibemusic.service.agent;

import cn.edu.seig.vibemusic.mapper.AgentSongMapper;
import cn.edu.seig.vibemusic.model.agent.SongRef;
import cn.edu.seig.vibemusic.model.vo.AgentSongVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agent 工具与真实歌曲数据之间的适配层。
 *
 * <p>职责单一：把数据库投影转成 {@link SongRef}。工具层只依赖本类，
 * 因此既不会污染传统 Service 的返回类型，也便于在评测中替换数据来源。
 */
@Service
@RequiredArgsConstructor
public class AgentSongQueryService {

    /** 单次工具调用最多返回的歌曲数，避免把过多内容塞进模型上下文。 */
    public static final int MAX_RESULTS = 20;

    private final AgentSongMapper songs;

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
        var result = songs.searchPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size),
                keyword, artistName, style);
        return new SongPage(toRefs(result.getRecords()), result.getTotal(), page, size);
    }

    public List<SongRef> search(String songName, String artistName, String album, String style, int limit) {
        return toRefs(songs.findSongs(blankToNull(songName), blankToNull(artistName),
                blankToNull(album), blankToNull(style), clamp(limit)));
    }

    /** 宽泛关键词检索：歌名/歌手/专辑/风格之间取 OR。 */
    public List<SongRef> searchByKeyword(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) return List.of();
        return toRefs(songs.findSongsByKeyword(keyword.trim(), clamp(limit)));
    }

    public List<SongRef> random(int limit) {
        return toRefs(songs.findRandomSongs(clamp(limit)));
    }

    public List<SongRef> byIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return toRefs(songs.findSongsByIds(ids.stream().filter(java.util.Objects::nonNull).distinct().toList()));
    }

    private static List<SongRef> toRefs(List<AgentSongVO> rows) {
        return rows.stream().map(AgentSongQueryService::toRef).toList();
    }

    private static SongRef toRef(AgentSongVO row) {
        return new SongRef(row.getSongId(), row.getSongName(), row.getArtistName(), row.getAlbum(),
                row.getStyle(), row.getDuration(), row.getCoverUrl(), row.getAudioUrl());
    }

    private static int clamp(int limit) {
        if (limit <= 0) return 10;
        return Math.min(limit, MAX_RESULTS);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
