package cn.edu.seig.vibemusic.service.cover;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 歌名 / 歌手 / 专辑名的归一化与比较。
 *
 * <p>刻意<b>不复用</b> {@code LyricsMatcher}：那边归一化的产物是「只剩字母数字」的
 * 一串无分隔文本（{@code 青花瓷Live}），适合打分但不适合做「歌名是否同一首」的判定 ——
 * 用它会连带把 {@code (Live)} 与 {@code (戏腔版)} 的差别一起抹平。
 * 封面需要的是「去掉版本后缀后仍然相等的歌名」以及「歌手集合是否有交集」。
 */
@Component
public class CoverNameNormalizer {

    /** 括号及其内容：() （） [] 【】 <> */
    private static final Pattern BRACKET =
            Pattern.compile("\\([^)]*\\)|（[^）]*）|\\[[^\\]]*]|【[^】]*】|<[^>]*>");

    /** feat./ft./featuring 及其之后的内容 */
    private static final Pattern FEAT =
            Pattern.compile("\\b(feat|ft|featuring)\\.?\\b.*$", Pattern.CASE_INSENSITIVE);

    /** 版本后缀关键词：出现这些词的部分在比较歌名时被忽略 */
    private static final Pattern VERSION_SUFFIX = Pattern.compile(
            "(?i)\\s*[-–—]?\\s*(live|现场|演唱会|伴奏|instrumental|karaoke|纯音乐|" +
                    "钢琴版|古筝版|吉他版|戏腔版|女声版|男声版|童声版|粤语|国语|英文版|" +
                    "remix|混音|dj版|dj|加速|变速|片段|翻唱|cover|demo|remaster(ed)?|" +
                    "\\d+\\.\\d+x)\\s*$");

    /** 除字母、数字、汉字之外的字符 */
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    /** 歌手分隔符：/ 、 , & 与「和」「与」 */
    private static final Pattern ARTIST_SEPARATOR =
            Pattern.compile("\\s*[/、,，&]\\s*|\\s+(?:and|feat\\.?|ft\\.?)\\s+|\\s*[和与]\\s*",
                    Pattern.CASE_INSENSITIVE);

    /**
     * 归一化歌名：去括号、去 feat、去版本后缀、去标点，小写。
     *
     * <p>例：{@code 青花瓷 (Live)} → {@code 青花瓷}；{@code 青花瓷 (戏腔版)} → {@code 青花瓷}。
     * 两者归一化后相同，说明它们是同一首歌的不同版本 —— 这正是封面候选要收录的范围，
     * 再由专辑去重与版本标签区分。
     */
    public String normalizeTitle(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String text = value.strip();
        text = BRACKET.matcher(text).replaceAll(" ");
        text = FEAT.matcher(text).replaceAll(" ");
        // 反复剥离：有些曲名是「青花瓷 - Live - 片段」这种多段后缀
        String previous;
        do {
            previous = text;
            text = VERSION_SUFFIX.matcher(text).replaceAll(" ");
        } while (!text.equals(previous));
        return NON_WORD.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    /** 归一化专辑名：只去标点与空白，保留完整文字（专辑名不做版本剥离）。 */
    public String normalizeAlbum(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return NON_WORD.matcher(value.strip().toLowerCase(Locale.ROOT)).replaceAll("");
    }

    /**
     * 归一化歌手名：拆成单歌手集合后排序拼接。
     *
     * <p>为什么要拆：QQ 音乐把多位歌手拼成 {@code 银临/Aki阿杰}，本地表单是
     * {@code 银临 / Aki阿杰}，直接比字符串会因空格不同而不等。拆成集合后
     * 顺序不同也能匹配。
     */
    public String normalizeArtist(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        List<String> parts = Arrays.stream(ARTIST_SEPARATOR.split(value.strip()))
                .map(part -> NON_WORD.matcher(part.toLowerCase(Locale.ROOT)).replaceAll(""))
                .filter(part -> !part.isEmpty())
                .sorted()
                .toList();
        return String.join("", parts);
    }

    /**
     * 歌手是否有交集。
     *
     * <p>用「交集非空」而不是「字符串相等」：候选可能是
     * {@code 周杰伦/江源东}（合唱版），本地只有 {@code 周杰伦}，仍应算匹配 ——
     * 否则合唱版的封面会被误判为「别人的翻唱」而丢弃。
     */
    public boolean artistMatches(String candidateArtist, String targetArtist) {
        if (!StringUtils.hasText(candidateArtist) || !StringUtils.hasText(targetArtist)) {
            return true;
        }
        List<String> candidateParts = splitArtist(candidateArtist);
        List<String> targetParts = splitArtist(targetArtist);
        for (String candidate : candidateParts) {
            for (String target : targetParts) {
                if (candidate.contains(target) || target.contains(candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> splitArtist(String value) {
        return Arrays.stream(ARTIST_SEPARATOR.split(value.strip()))
                .map(part -> NON_WORD.matcher(part.toLowerCase(Locale.ROOT)).replaceAll(""))
                .filter(part -> !part.isEmpty())
                .toList();
    }
}
