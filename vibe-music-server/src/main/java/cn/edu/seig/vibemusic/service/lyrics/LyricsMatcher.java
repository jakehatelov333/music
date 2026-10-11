package cn.edu.seig.vibemusic.service.lyrics;

import cn.edu.seig.vibemusic.config.LyricsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 歌词候选匹配打分。
 * <p>
 * 库内歌名普遍不规范（例如 {@code Positions (Deluxe}、{@code ÷ (Deluxe}、{@code Fool's Gold}），
 * 因此必须先归一化再比对，并叠加歌手与<b>时长</b>两个维度的权重；
 * 时长能有效区分同一首歌的录音室版 / Live / Remix，是避免错配的关键。
 * <p>
 * 得分低于阈值的候选一律丢弃——宁可不写，也不要写入错误的歌词。
 */
@Slf4j
@Component
public class LyricsMatcher {

    /** 括号内容（中英文、方括号、书名号） */
    private static final Pattern BRACKET = Pattern.compile("\\([^)]*\\)|（[^）]*）|\\[[^\\]]*]|【[^】]*】");
    /** feat./ft./featuring 及其之后的内容 */
    private static final Pattern FEAT = Pattern.compile("\\b(feat|ft|featuring)\\.?\\b.*$", Pattern.CASE_INSENSITIVE);
    /** 除字母、数字、汉字之外的全部字符 */
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    @Autowired
    private LyricsProperties properties;

    /** 打分结果 */
    public record Scored(LyricsCandidate candidate, double score) {
    }

    /**
     * 归一化：小写 → 去括号 → 去 feat → 去标点空白。
     */
    public String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String text = value.strip().toLowerCase(Locale.ROOT);
        text = BRACKET.matcher(text).replaceAll("");
        text = FEAT.matcher(text).replaceAll("");
        return NON_WORD.matcher(text).replaceAll("");
    }

    /**
     * 计算候选与查询条件的匹配得分。
     */
    public double score(LyricsCandidate candidate, LyricsProvider.Query query) {
        String targetTrack = normalize(query.trackName());
        String targetArtist = normalize(query.artistName());
        String targetAlbum = normalize(query.albumName());

        String track = normalize(candidate.trackName());
        String artist = normalize(candidate.artistName());
        String album = normalize(candidate.albumName());

        double score = 0d;

        if (StringUtils.hasText(targetTrack) && targetTrack.equals(track)) {
            score += 0.6;
        } else if (StringUtils.hasText(targetTrack) && (track.contains(targetTrack) || targetTrack.contains(track))) {
            score += 0.4;
        }

        if (StringUtils.hasText(targetArtist) && StringUtils.hasText(artist)) {
            if (targetArtist.equals(artist)) {
                score += 0.3;
            } else if (artist.contains(targetArtist) || targetArtist.contains(artist)) {
                score += 0.15;
            }
        }

        if (StringUtils.hasText(targetAlbum) && StringUtils.hasText(album) && targetAlbum.equals(album)) {
            score += 0.1;
        }

        score += durationBonus(query.durationSeconds(), candidate.durationSeconds());

        return score;
    }

    private double durationBonus(Double target, Integer candidate) {
        if (target == null || target <= 0 || candidate == null || candidate <= 0) {
            return 0d;
        }
        double delta = Math.abs(candidate - target);
        int tolerance = Math.max(1, properties.getMatch().getDurationToleranceSeconds());
        if (delta <= tolerance) {
            return 0.1;
        }
        if (delta <= tolerance * 2.5) {
            return 0.05;
        }
        if (delta > 20) {
            // 时长差过大，几乎可以确定不是同一个版本
            return -0.2;
        }
        return 0d;
    }

    /**
     * 从候选中挑出得分最高者；低于阈值返回 null。
     */
    public Scored pickBest(List<LyricsCandidate> candidates, LyricsProvider.Query query) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        LyricsCandidate best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (LyricsCandidate candidate : candidates) {
            if (candidate == null || !StringUtils.hasText(candidate.lyric())) {
                continue;
            }
            double score = score(candidate, query);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        if (best == null) {
            return null;
        }
        double minScore = properties.getMatch().getMinScore();
        if (bestScore < minScore) {
            log.debug("歌词候选最高分 {} 低于阈值 {}，判定为无匹配：{} - {}",
                    bestScore, minScore, query.artistName(), query.trackName());
            return null;
        }
        return new Scored(best, bestScore);
    }

}
