package cn.edu.seig.vibemusic.service.artist;

import cn.edu.seig.vibemusic.service.cover.CoverNameNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 歌手候选的匹配与排序。
 *
 * <h3>为什么不能"搜到就直接用第一条"</h3>
 * 对库里 129 位歌手做了全量实测：122 位原名直接命中，4 位去掉尾部括号后命中，
 * 3 位完全搜不到。但命中<b>不等于</b>对上了人 ——
 * <ul>
 *   <li>{@code LANY} 的第一条候选是 {@code Blue / 蓝又时}，完全无关；</li>
 *   <li>{@code Christopher} 的第一条是 {@code 黄凯芹}，第二条才是他自己；</li>
 *   <li>候选里还混着 {@code 周杰伦战队}、{@code 邓紫棋的棋士}、{@code 五月天 阿信}
 *       这类衍生账号，名字包含原名但根本不是同一位歌手。</li>
 * </ul>
 * 所以：搜索只负责<b>给候选</b>，由管理员点选；排序只负责把最可能的排在前面，
 * 绝不代替人做决定。
 *
 * <h3>去括号回退</h3>
 * 库里存在 {@code IU (아이유)}、{@code 孝敏 (효민)}、{@code DAISHI DANCE (ダイシ・ダンス)}
 * 这种「中文名 + 原文名」的写法，QQ 侧只按主名收录，原样搜是空的。去掉尾部括号后
 * 4 位全部命中。这是有损操作（可能撞上同名），所以只作为<b>第二段</b>尝试，
 * 且结果要标注出来让管理员知道名字不完全一致。
 */
@Slf4j
@Component
public class ArtistCandidateRanker {

    /** 尾部括号（半角/全角）及其内容。只处理结尾，避免误伤 {@code G.E.M.邓紫棋} 这类中间带点的名字。 */
    private static final Pattern TRAILING_BRACKET =
            Pattern.compile("\\s*[(（][^()（）]*[)）]\\s*$");

    /** 除字母、数字、汉字之外的字符。 */
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    /** 排序权重：完全一致 > 互为子串 > 毫无关系。 */
    static final int SCORE_EXACT = 100;
    static final int SCORE_CONTAINS = 50;
    static final int SCORE_UNRELATED = 10;

    /**
     * 「包含」关系的最低长度。
     *
     * <p>太短的名字（如 {@code IU}）用 contains 判断会把 {@code iukoi77} 也算成相关，
     * 所以要求双方归一化后都不少于这个长度才承认 contains。
     */
    static final int MIN_CONTAINS_LENGTH = 3;

    @Autowired
    private CoverNameNormalizer normalizer;

    /** 一个带评分的候选。 */
    public record Ranked<T>(T hit, int score, boolean exact) {
    }

    /**
     * 归一化歌手名用于比较：小写、去尾部括号、去标点。
     *
     * <p>例：{@code IU (아이유)} → {@code iu}；{@code G.E.M.邓紫棋} → {@code gem邓紫棋}。
     */
    public String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String text = TRAILING_BRACKET.matcher(value.strip()).replaceAll("");
        return NON_WORD.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    /** 去掉尾部括号后的名字；没有括号时原样返回。 */
    public String stripTrailingBracket(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return TRAILING_BRACKET.matcher(value.strip()).replaceAll("").trim();
    }

    /** 该名字是否带尾部括号（用于判断要不要做第二段搜索）。 */
    public boolean hasTrailingBracket(String value) {
        return StringUtils.hasText(value) && TRAILING_BRACKET.matcher(value.strip()).find();
    }

    /**
     * 给候选打分并降序排序。
     *
     * <p>排序稳定：同分时保持搜索接口给出的原始顺序 —— 那是 QQ 自己的相关度排序，
     * 在"我们判断不了谁更对"的情况下，尊重它比随机打乱更合理。
     *
     * @param keyword 用户输入的（或库里的）歌手名
     * @param hits    搜索返回的候选
     * @return 按分数降序的候选；{@code hits} 为空时返回空列表
     */
    public <T> List<Ranked<T>> rank(String keyword, List<T> hits, java.util.function.Function<T, String> nameOf) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        String target = normalize(keyword);
        List<Ranked<T>> ranked = new ArrayList<>(hits.size());
        for (T hit : hits) {
            String name = nameOf.apply(hit);
            int score = score(target, normalize(name));
            ranked.add(new Ranked<>(hit, score, score == SCORE_EXACT));
        }
        // 稳定排序：同分保持原始顺序
        ranked.sort(Comparator.comparingInt(Ranked<T>::score).reversed());
        return ranked;
    }

    /**
     * 计算匹配分。
     *
     * <p>刻意把"互为子串"和"完全一致"分开：{@code 周杰伦} 与 {@code 周杰伦战队} 是包含关系，
     * 但后者是衍生账号，不能和本尊同分。
     */
    int score(String target, String candidate) {
        if (target.isEmpty() || candidate.isEmpty()) {
            return SCORE_UNRELATED;
        }
        if (target.equals(candidate)) {
            return SCORE_EXACT;
        }
        // 太短的名字不做 contains 判断，否则 IU 会把 iukoi77 也算成相关
        if (target.length() >= MIN_CONTAINS_LENGTH && candidate.length() >= MIN_CONTAINS_LENGTH
                && (candidate.contains(target) || target.contains(candidate))) {
            return SCORE_CONTAINS;
        }
        // 拼音/外文名的近似：只做前缀判断，避免把完全无关的名字抬上来
        if (target.length() >= MIN_CONTAINS_LENGTH && candidate.startsWith(target)) {
            return SCORE_CONTAINS;
        }
        return SCORE_UNRELATED;
    }

    /**
     * 判断某候选是否值得自动预选。
     *
     * <p>只有<b>归一化后完全一致</b>才自动选中（例如 {@code RADWIMPS (ラッドウィンプス)}
     * 搜到 {@code RADWIMPS}、{@code IU (아이유)} 搜到 {@code IU}）。
     * 包含关系（{@code 周杰伦} vs {@code 周杰伦战队}）一律<b>不</b>自动选，
     * 因为那正是最容易选错的一类。
     */
    public boolean isSafeToPreselect(String keyword, String candidateName) {
        String target = normalize(keyword);
        return !target.isEmpty() && target.equals(normalize(candidateName));
    }

    /** 候选名与输入名是否"看起来不是同一位"（用于前端提示）。 */
    public boolean looksDifferent(String keyword, String candidateName) {
        return !isSafeToPreselect(keyword, candidateName);
    }
}
