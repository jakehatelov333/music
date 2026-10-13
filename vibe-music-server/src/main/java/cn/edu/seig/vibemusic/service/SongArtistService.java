package cn.edu.seig.vibemusic.service;

import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.mapper.SongArtistMapper;
import cn.edu.seig.vibemusic.enumeration.ArtistStatusEnum;
import cn.edu.seig.vibemusic.model.agent.SongArtistRef;
import cn.edu.seig.vibemusic.model.entity.Artist;
import cn.edu.seig.vibemusic.model.vo.ArtistNameVO;
import cn.edu.seig.vibemusic.model.vo.SongArtistAware;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 歌曲-演唱歌手关系的统一入口（<b>普通 {@code @Service} 组件，不是接口</b>）。
 *
 * <p>本项目传统 Service 采用「接口 + impl」两段式，但本类刻意做成单一具体类：
 * 它的职责边界很窄（关系表读写 + 展示串派生），没有多实现的必要，
 * 拆成接口只会增加一层跳转。与 {@code service/agent/} 下的新组件保持同一取向。
 *
 * <p><b>三条不可违反的规则</b>（对应 docs/phase6-song-multiple-artists.md §2.3）：
 * <ol>
 *     <li>判断「某歌手是否演唱某歌」只能查 {@code tb_song_artist}，不得用 {@code artist_names LIKE}；</li>
 *     <li>{@code artist_names} 只能由关系表 + {@code tb_artist.name} 派生，
 *         派生实现全项目唯一（{@link #renderArtistNames}）；</li>
 *     <li>读路径不得 JOIN {@code tb_song_artist} —— 装配一律走 {@link #findArtistsBySongIds}
 *         的独立批量查询，避免分页 COUNT 被关联行放大。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SongArtistService {

    /**
     * 展示串分隔符。用「空格 + 斜杠 + 空格」而不是逗号：
     * 库里存在 {@code id=3「接个吻，开一枪」} 这种含中文逗号的合法艺名，逗号会造成歧义。
     */
    public static final String DISPLAY_SEPARATOR = " / ";

    private final SongArtistMapper songArtistMapper;
    private final ArtistMapper artistMapper;

    /**
     * 按顺序把歌手名拼成展示串。全项目唯一的拼接实现，写入路径与歌手改名都必须走这里，
     * 否则会出现两套拼接规则各自漂移。
     *
     * @return 空/全空白时返回 {@code null}（表示「没有歌手」），而不是空串
     */
    public static String renderArtistNames(List<String> names) {
        if (names == null) {
            return null;
        }
        String joined = names.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .collect(Collectors.joining(DISPLAY_SEPARATOR));
        return joined.isEmpty() ? null : joined;
    }

    /**
     * 批量取「歌曲 id → 歌手列表」。一次往返，避免逐首歌查询造成 N+1。
     *
     * <p>列表顺序即 {@code display_order} 顺序（由 SQL 保证），因此调用方不需要二次排序。
     * 没有任何关系的歌曲不会出现在返回的 Map 里，调用方用 {@code getOrDefault(id, List.of())} 兜底。
     */
    public Map<Long, List<ArtistNameVO>> findArtistsBySongIds(Collection<Long> songIds) {
        if (songIds == null) {
            return Map.of();
        }
        List<Long> ids = songIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<ArtistNameVO>> grouped = new LinkedHashMap<>();
        for (SongArtistRef row : songArtistMapper.findBySongIds(ids)) {
            grouped.computeIfAbsent(row.getSongId(), key -> new ArrayList<>())
                    .add(toArtistNameVO(row));
        }
        return grouped;
    }

    /** 单首歌的歌手列表；无关系时返回不可变空列表。 */
    public List<ArtistNameVO> findArtistsBySongId(Long songId) {
        if (songId == null) {
            return List.of();
        }
        return findArtistsBySongIds(List.of(songId)).getOrDefault(songId, List.of());
    }

    /**
     * 就地给一批歌曲视图装配 {@code artists}。无关系时写入<b>空列表</b>而不是 null，
     * 让前端不必区分「字段缺失」与「确实没有歌手」。
     */
    public void attachArtists(List<? extends SongArtistAware> songs) {
        if (songs == null || songs.isEmpty()) {
            return;
        }
        Map<Long, List<ArtistNameVO>> grouped = findArtistsBySongIds(
                songs.stream().map(SongArtistAware::getSongId).toList());
        for (SongArtistAware song : songs) {
            song.setArtists(grouped.getOrDefault(song.getSongId(), List.of()));
        }
    }

    /** 单首歌视图的装配重载。 */
    public void attachArtists(SongArtistAware song) {
        if (song == null) {
            return;
        }
        attachArtists(List.of(song));
    }

    private static ArtistNameVO toArtistNameVO(SongArtistRef row) {
        ArtistNameVO vo = new ArtistNameVO();
        vo.setArtistId(row.getArtistId());
        vo.setArtistName(row.getArtistName());
        return vo;
    }

    // ================================================================== 写操作

    /**
     * 规范化歌手 id 列表：过滤空值与非正数、去重，并<b>保持传入顺序</b>（顺序即展示顺序）。
     *
     * <p>{@code artistIds} 为空时回退到单个 {@code fallbackArtistId}，用于兼容只传旧字段
     * {@code artistId} 的老客户端。两者都为空则返回空列表，由调用方决定是报错还是保持不变。
     */
    public static List<Long> normalizeArtistIds(List<Long> artistIds, Long fallbackArtistId) {
        List<Long> source = (artistIds == null || artistIds.isEmpty())
                ? (fallbackArtistId == null ? List.of() : List.of(fallbackArtistId))
                : artistIds;
        return source.stream()
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .toList();
    }

    /**
     * 整组替换某首歌的演唱歌手：关系表与 {@code artist_id} / {@code artist_names} 在同一事务内更新。
     *
     * <p><b>为什么必须整组替换：</b>{@code display_order} 刻意没有唯一约束（避免交换顺序时撞键），
     * 所以顺序只能先删后插地整体重建。一首歌的歌手数极少，这个代价可以忽略。
     *
     * <p><b>事务内自检</b>是最后一道闸：写完立刻回查行数，不一致就抛异常回滚，
     * 杜绝「展示串已改、关系表只写了一半」这种半成品状态。
     *
     * @param artistIds 有序歌手 id；第一个即主艺人（写入 {@code tb_song.artist_id}）
     */
    @Transactional(rollbackFor = Exception.class)
    public void replaceSongArtists(Long songId, List<Long> artistIds) {
        if (songId == null) {
            throw new IllegalArgumentException("歌曲 id 不能为空");
        }
        List<Long> ordered = normalizeArtistIds(artistIds, null);
        if (ordered.isEmpty()) {
            throw new IllegalArgumentException("歌曲至少需要一位歌手");
        }

        List<Artist> artists = loadArtistsInOrder(ordered);
        Long primaryArtistId = artists.get(0).getArtistId();
        String artistNames = renderArtistNames(artists.stream().map(Artist::getArtistName).toList());

        if (songArtistMapper.updateSongArtistSummary(songId, primaryArtistId, artistNames) == 0) {
            throw new IllegalStateException("歌曲不存在，无法写入歌手关系：" + songId);
        }
        songArtistMapper.deleteBySongId(songId);
        songArtistMapper.insertRelations(songId, ordered);

        int actual = songArtistMapper.countBySongId(songId);
        if (actual != ordered.size()) {
            throw new IllegalStateException(
                    "歌手关系写入不完整：期望 " + ordered.size() + " 行，实际 " + actual + " 行");
        }
    }

    /**
     * 歌手改名后重建其参与歌曲的展示串。
     *
     * <p><b>为什么在 Java 里逐首重建，而不是一条 {@code UPDATE ... JOIN ... GROUP_CONCAT}：</b>
     * {@code GROUP_CONCAT} 的 {@code ORDER BY} 在 H2 上不可移植（测试基座跑 H2(MODE=MySQL)），
     * 而 Java 重建可移植、可单测，且与写入路径<b>共用同一个 {@link #renderArtistNames}</b>，
     * 从根上杜绝两套拼接规则漂移。歌手改名是低频操作，受影响歌曲数量级完全可接受。
     *
     * @return 实际更新的歌曲数
     */
    @Transactional(rollbackFor = Exception.class)
    public int rebuildArtistNamesForArtist(Long artistId) {
        if (artistId == null) {
            return 0;
        }
        List<Long> songIds = songArtistMapper.findSongIdsByArtistId(artistId);
        if (songIds.isEmpty()) {
            return 0;
        }
        Map<Long, List<ArtistNameVO>> grouped = findArtistsBySongIds(songIds);
        int updated = 0;
        for (Long songId : songIds) {
            List<ArtistNameVO> list = grouped.get(songId);
            if (list == null || list.isEmpty()) {
                // 关系可能已被并发清空；跳过而不是写入空展示串，避免把歌曲变成「无歌手」。
                log.warn("歌手改名重建展示串时发现空关系，已跳过 songId={} artistId={}", songId, artistId);
                continue;
            }
            String names = renderArtistNames(list.stream().map(ArtistNameVO::getArtistName).toList());
            updated += songArtistMapper.updateSongArtistSummary(songId, list.get(0).getArtistId(), names);
        }
        return updated;
    }

    /**
     * 按传入顺序加载歌手实体，任一位不存在<b>或已停用</b>立即抛异常（不静默跳过）。
     * 调用方通常处于事务中，异常会回滚整个写入。
     */
    private List<Artist> loadArtistsInOrder(List<Long> orderedIds) {
        Map<Long, Artist> byId = artistMapper.selectBatchIds(orderedIds).stream()
                .collect(Collectors.toMap(Artist::getArtistId, Function.identity()));
        List<Artist> result = new ArrayList<>(orderedIds.size());
        for (Long id : orderedIds) {
            Artist artist = byId.get(id);
            if (artist == null) {
                throw new IllegalArgumentException("歌手不存在：" + id);
            }
            if (!ArtistStatusEnum.isActive(artist.getStatus())) {
                // 停用（软删除）的歌手不得被新歌曲引用 —— 否则会重新把「已合并的组合歌手」挂回歌曲上。
                throw new IllegalArgumentException("歌手已停用，不能作为演唱者：" + artist.getArtistName());
            }
            result.add(artist);
        }
        return result;
    }
}
