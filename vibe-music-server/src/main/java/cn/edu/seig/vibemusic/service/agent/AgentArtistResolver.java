package cn.edu.seig.vibemusic.service.agent;

import cn.edu.seig.vibemusic.enumeration.ArtistStatusEnum;
import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.model.entity.Artist;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 把用户/模型给出的「歌手名」解析成 {@code tb_artist} 的启用歌手 id 集合。
 *
 * <p><b>为什么需要它：</b>多歌手改造后，「某歌手是否演唱某歌」的唯一权威依据是关系表
 * {@code tb_song_artist}，而 {@code tb_song.artist_names} 只是派生展示串。
 * 直接用 {@code artist_names LIKE} 做歌手筛选，本质上是在派生值上做子串匹配：
 * 既能被跨分隔符的片段误命中（例如展示串 {@code 银临 / Aki阿杰} 会被 {@code 临 / Aki} 命中），
 * 也无法表达「这位歌手参与了这首歌」的归属语义。
 *
 * <p>因此检索侧统一改为两步：先在本类把名字解析成 id 集合，再交给
 * {@code AgentSongMapper} 用 {@code EXISTS} 查关系表。本类只做「名字 → id」这一步，
 * 不参与任何歌曲查询。
 *
 * <p><b>解析规则：</b>
 * <ol>
 *     <li>整体串本身作为第一个候选（覆盖「用户直接粘贴完整展示串」的情况）；</li>
 *     <li>再按 {@code /} 与 {@code 、} 拆分，把每一段也作为候选。
 *         这是为了兼容把展示串 {@code 银临 / Aki阿杰} 原样传进来的调用方 ——
 *         若不拆分，整串既不是任何一位启用歌手的名字，也会导致召回凭空丢失；</li>
 *     <li>逐段走 {@code name LIKE '%候选%'}，并<b>只保留启用歌手</b>
 *         （停用/已合并的组合记录不得再被检索到）。</li>
 * </ol>
 *
 * <p>分隔符刻意不含逗号：库里存在 {@code 「接个吻，开一枪」} 这种含中文逗号的合法艺名。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentArtistResolver {

    /**
     * 可用于拆分的分隔符。{@code /} 与 {@code SongArtistService.DISPLAY_SEPARATOR} 一致；
     * {@code 、} 是用户列举多位歌手时的常见写法。
     */
    private static final String[] SEPARATORS = {"/", "、"};

    /**
     * 单次解析最多收集的歌手数。宽泛关键词（例如「a」）可能命中大量歌手，
     * 若不设上限会把 {@code IN (...)} 撑得很大；上限之外的部分按 id 升序截断。
     */
    private static final int MAX_RESOLVED_ARTISTS = 50;

    private final ArtistMapper artistMapper;

    /**
     * 解析歌手名/关键词为启用状态的 {@code artistId} 集合。
     *
     * @param artistName 歌手名、关键词，或完整展示串（例如 {@code 银临 / Aki阿杰}）
     * @return 去重后的 id 列表（按 id 升序）；无法解析时返回<b>空列表</b>，
     *         由调用方决定是「查不到」还是「不加该条件」
     */
    public List<Long> resolveActiveArtistIds(String artistName) {
        List<String> tokens = tokens(artistName);
        if (tokens.isEmpty()) {
            return List.of();
        }

        LambdaQueryWrapper<Artist> wrapper = new LambdaQueryWrapper<>();
        // 停用（软删除）歌手不得参与检索；like 之间是 OR，必须用 and(...) 包住，
        // 否则 OR 会吃掉前面的 status 条件，把停用歌手重新放出来。
        wrapper.eq(Artist::getStatus, ArtistStatusEnum.ACTIVE.getStatus())
                .and(q -> {
                    for (int i = 0; i < tokens.size(); i++) {
                        if (i > 0) {
                            q.or();
                        }
                        q.like(Artist::getArtistName, tokens.get(i));
                    }
                })
                .orderByAsc(Artist::getArtistId)
                .last("LIMIT " + MAX_RESOLVED_ARTISTS);

        List<Artist> found = artistMapper.selectList(wrapper);
        Set<Long> ids = new LinkedHashSet<>();
        for (Artist artist : found) {
            if (artist != null && artist.getArtistId() != null) {
                ids.add(artist.getArtistId());
            }
        }
        if (ids.isEmpty()) {
            log.debug("歌手名未能解析出启用歌手：{}", artistName);
        }
        return List.copyOf(ids);
    }

    /** 拆出候选片段：整体串在前，按分隔符拆出的各段在后，保持顺序并去重。 */
    private static List<String> tokens(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        String value = raw.trim();
        List<String> tokens = new ArrayList<>();
        tokens.add(value);
        for (String separator : SEPARATORS) {
            if (!value.contains(separator)) {
                continue;
            }
            for (String part : value.split(Pattern.quote(separator))) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    tokens.add(trimmed);
                }
            }
        }
        return tokens.stream().distinct().toList();
    }
}
