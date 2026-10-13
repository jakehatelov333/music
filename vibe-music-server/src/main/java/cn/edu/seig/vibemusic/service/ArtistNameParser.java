package cn.edu.seig.vibemusic.service;

import cn.edu.seig.vibemusic.enumeration.ArtistStatusEnum;
import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.model.entity.Artist;
import cn.edu.seig.vibemusic.model.vo.ArtistParsingVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 上传元数据里歌手字段的解析器（对应 docs/phase6-song-multiple-artists.md §6）。
 *
 * <p><b>为什么需要它：</b>改造前，上传《牵丝戏》时元数据 artist = {@code 银临/Aki阿杰}
 * 匹配不到现有歌手，就被 {@code SongUploadServiceImpl.createArtist} 直接建成了一条
 * 名叫「银临/Aki阿杰」的伪歌手记录 —— 组合记录就是这么产生的。本解析器把这件事
 * 从「无脑新建」改成「有条件拆分 + 人工确认」。
 *
 * <p><b>判定优先级（严格按序短路）：</b>
 * <ol>
 *     <li>完整文本与某位现有歌手精确匹配 → 视为单一歌手，<b>绝不拆分</b>
 *         （保护 {@code AC/DC} 这类本身含斜杠的合法艺名）；</li>
 *     <li>否则尝试识别明确的分隔符；识别不到 → {@code UNRESOLVED}；</li>
 *     <li>拆出的每一段都能<b>唯一</b>精确匹配现有歌手 → {@code SPLIT_CONFIRMED}；</li>
 *     <li>存在未匹配或歧义 → {@code SPLIT_CANDIDATE}，交由人工确认。</li>
 * </ol>
 *
 * <p><b>两条硬约束：</b>未识别的名称既不会被自动创建，也不会被静默丢弃 ——
 * 它们原样出现在 {@code candidates} 里等管理员处理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArtistNameParser {

    /**
     * 明确的分隔符白名单。
     *
     * <p>⚠️ <b>刻意不含逗号</b>：库里存在 {@code id=3「接个吻，开一枪」} 这种含中文逗号的
     * 合法艺名，用逗号当分隔符会把它拆坏。
     */
    private static final Pattern SEPARATOR = Pattern.compile(
            "\\s*/\\s*|\\s*&\\s*|\\s*、\\s*|\\s+(?:feat|ft|featuring)\\.?\\s+",
            Pattern.CASE_INSENSITIVE);

    public static final String STRATEGY_EXACT_MATCH = "EXACT_MATCH";
    public static final String STRATEGY_SPLIT_CONFIRMED = "SPLIT_CONFIRMED";
    public static final String STRATEGY_SPLIT_CANDIDATE = "SPLIT_CANDIDATE";
    public static final String STRATEGY_UNRESOLVED = "UNRESOLVED";

    private final ArtistMapper artistMapper;

    /** 文本里是否含明确的分隔符（用于决定「拒绝自动建歌手」还是「照旧建单歌手」）。 */
    public boolean looksSplittable(String raw) {
        return raw != null && SEPARATOR.matcher(raw).find();
    }

    /** 解析歌手文本；任何异常路径都只返回 UNRESOLVED，不抛异常、不写库。 */
    public ArtistParsingVO parse(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return build(raw, STRATEGY_UNRESOLVED, null, List.of());
        }

        // 1. 完整匹配优先：本身就是一位现有歌手，绝不拆分。
        List<Artist> exact = findExact(value);
        if (exact.size() == 1) {
            return build(raw, STRATEGY_EXACT_MATCH, null,
                    List.of(candidate(exact.get(0).getArtistId(), exact.get(0).getArtistName(), true)));
        }

        // 2. 识别分隔符
        Matcher matcher = SEPARATOR.matcher(value);
        if (!matcher.find()) {
            return build(raw, STRATEGY_UNRESOLVED, null,
                    List.of(candidate(null, value, false)));
        }
        String separator = matcher.group().trim();

        List<String> parts = splitParts(value);
        if (parts.size() < 2) {
            return build(raw, STRATEGY_UNRESOLVED, separator,
                    List.of(candidate(null, value, false)));
        }

        // 3. 每一段都必须唯一精确匹配现有歌手，才允许自动拆分。
        List<ArtistParsingVO.Candidate> candidates = new ArrayList<>(parts.size());
        boolean allMatched = true;
        for (String part : parts) {
            List<Artist> hits = findExact(part);
            if (hits.size() == 1) {
                candidates.add(candidate(hits.get(0).getArtistId(), hits.get(0).getArtistName(), true));
            } else {
                // 0 条（未知歌手）或 ≥2 条（同名歧义）都算未匹配，交由人工确认。
                candidates.add(candidate(null, part, false));
                allMatched = false;
            }
        }
        return build(raw, allMatched ? STRATEGY_SPLIT_CONFIRMED : STRATEGY_SPLIT_CANDIDATE,
                separator, candidates);
    }

    // ------------------------------------------------------------------ 内部

    /** 按分隔符切片并去掉空白片段；顺序保持原文顺序（即建议的展示顺序）。 */
    private static List<String> splitParts(String value) {
        List<String> parts = new ArrayList<>();
        for (String part : SEPARATOR.split(value)) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }

    /** 精确匹配<b>启用中</b>的歌手名。返回多条表示存在同名歧义，调用方按「未匹配」处理。 */
    private List<Artist> findExact(String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        return artistMapper.selectList(new LambdaQueryWrapper<Artist>()
                .eq(Artist::getArtistName, name.trim())
                .eq(Artist::getStatus, ArtistStatusEnum.ACTIVE.getStatus()));
    }

    private static ArtistParsingVO.Candidate candidate(Long artistId, String artistName, boolean matched) {
        ArtistParsingVO.Candidate item = new ArtistParsingVO.Candidate();
        item.setArtistId(artistId);
        item.setArtistName(artistName);
        item.setMatched(matched);
        return item;
    }

    private static ArtistParsingVO build(String raw, String strategy, String separator,
                                         List<ArtistParsingVO.Candidate> candidates) {
        ArtistParsingVO vo = new ArtistParsingVO();
        vo.setRaw(raw);
        vo.setStrategy(strategy);
        vo.setSeparator(separator);
        vo.setCandidates(candidates);
        return vo;
    }
}
