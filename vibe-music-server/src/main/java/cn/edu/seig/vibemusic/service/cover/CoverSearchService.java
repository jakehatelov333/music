package cn.edu.seig.vibemusic.service.cover;

import cn.edu.seig.vibemusic.model.dto.CoverSearchDTO;
import cn.edu.seig.vibemusic.model.vo.CoverCandidateVO;
import cn.edu.seig.vibemusic.service.lyrics.QqMusicCatalog;
import cn.edu.seig.vibemusic.service.lyrics.QqSongHit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 按歌名/歌手/专辑搜索 QQ 音乐专辑封面。
 *
 * <h3>为什么不能直接把搜索结果当候选</h3>
 * 实测搜「淘金小镇 周杰伦」返回 8 条，只有 1 条是目标歌曲，其余是《稻香》《夜曲》
 * 《兰亭序》。如果把搜索结果直接排成封面列表，等于把周杰伦别的专辑封面摆给用户挑 ——
 * 选出来的封面和歌曲毫无关系，而且用户很可能看不出来。
 *
 * <p>所以自动模式必须做三件事：<b>先按歌名+歌手过滤，再按专辑去重，最后按得分排序</b>。
 *
 * <h3>过滤为什么用「歌名完全相等」而不是包含</h3>
 * 包含匹配会把《青花瓷 (Live)》之外的《青花瓷 (戏腔版)》《青花瓷 (古筝版)》也放进来，
 * 但它们分属不同专辑、封面完全不同。用完全相等 + 去括号归一化后，这些版本会被
 * 归到同一个歌名（括号内容被 {@link CoverNameNormalizer#normalizeTitle} 去掉），
 * 再靠歌手维度与专辑去重收敛 —— 既能拿到 Live 版封面，又不会混进无关翻唱。
 *
 * <h3>手动模式为什么不做过滤</h3>
 * 音频标签解析错误时（歌名带乱码、歌手填成文件名），拿错误元数据过滤只会把
 * 正确结果筛掉。此时管理员自己输入正确关键词，服务端原样检索并按专辑聚拢，
 * 不再做任何「猜」的动作。
 */
@Slf4j
@Service
public class CoverSearchService {

    /** 自动模式默认候选数：有过滤，结果天然收敛，4 个够选 */
    static final int AUTO_LIMIT = 4;

    /** 手动模式默认候选数：无过滤，召回里混着同名不同版本，需要更宽的选择面 */
    static final int MANUAL_LIMIT = 8;

    /** 候选数硬上限，防止调用方传入超大值把响应撑爆 */
    static final int MAX_LIMIT = 12;

    /**
     * 版本后缀识别：Live / 翻唱 / 伴奏 / 混音 等。
     *
     * <p>中英文分开写：{@code \b} 在 Java 里只认 {@code [a-zA-Z0-9_]}，汉字不属于单词字符，
     * 所以 {@code \b现场} 这种写法永远匹配不上（"青花瓷 现场版"里空格与「现」之间
     * 不存在 word boundary）。英文词用 {@code \b} 防止误伤（如 "alive" 里的 "live"），
     * 中文词直接匹配即可。
     */
    private static final Pattern LIVE = Pattern.compile(
            "(?i)\\blive\\b|演唱会|现场|音乐会");
    private static final Pattern COVER = Pattern.compile(
            "(?i)\\bcover\\b|翻唱|好声音|我是歌手|蒙面唱将|中国新声代");
    private static final Pattern INSTRUMENTAL = Pattern.compile(
            "(?i)\\b(instrumental|karaoke)\\b|伴奏|纯音乐|钢琴版|古筝版|吉他版");
    private static final Pattern REMIX = Pattern.compile(
            "(?i)\\b(remix|dj)\\b|混音|dj版|加速|变速|\\d+\\.\\d+x");

    @Autowired
    private QqMusicCatalog catalog;

    @Autowired
    private CoverNameNormalizer normalizer;

    /**
     * 搜索封面候选。
     *
     * @return 候选列表；无结果时返回空列表（不抛异常）
     */
    public List<CoverCandidateVO> search(CoverSearchDTO dto) {
        if (dto == null) {
            return List.of();
        }
        boolean manual = StringUtils.hasText(dto.getKeyword());
        if (!manual && !StringUtils.hasText(dto.getSongName())) {
            return List.of();
        }

        int limit = resolveLimit(dto.getLimit(), manual);
        List<QqSongHit> hits = manual
                ? catalog.search(dto.getKeyword().trim(), QqMusicCatalog.MAX_LIMIT)
                : searchAuto(dto);

        List<CoverCandidateVO> candidates = manual
                ? collectAll(hits, dto.getKeyword().trim(), limit)
                : collectFiltered(hits, dto, limit);

        log.debug("封面搜索完成：manual={}，命中 {} 条，候选 {} 个", manual, hits.size(), candidates.size());
        return candidates;
    }

    /**
     * 自动模式的关键词尝试顺序：先「歌名 歌手」，命中不足再退化为纯歌名。
     *
     * <p>与歌词链路同样的理由：组合关键词可能返回专辑里其它曲目，反而搜不到目标歌，
     * 所以必须按「过滤后的候选数」而不是「结果是否为空」决定是否退化。
     */
    private List<QqSongHit> searchAuto(CoverSearchDTO dto) {
        String track = dto.getSongName().trim();
        String artist = StringUtils.hasText(dto.getArtistName()) ? dto.getArtistName().trim() : null;
        String combined = artist == null ? track : track + " " + artist;

        List<QqSongHit> hits = catalog.search(combined, QqMusicCatalog.MAX_LIMIT);
        if (countUsable(hits, dto) >= AUTO_LIMIT) {
            return hits;
        }
        if (!combined.equals(track)) {
            List<QqSongHit> fallback = catalog.search(track, QqMusicCatalog.MAX_LIMIT);
            // 合并两轮结果：组合关键词可能已经找到部分正确项，退化检索是补充而不是替换
            return merge(hits, fallback);
        }
        return hits;
    }

    /** 合并两轮检索结果，按 songMid 去重，保持原有顺序。 */
    private List<QqSongHit> merge(List<QqSongHit> first, List<QqSongHit> second) {
        Map<String, QqSongHit> merged = new LinkedHashMap<>();
        for (QqSongHit hit : first) {
            merged.putIfAbsent(hitKey(hit), hit);
        }
        for (QqSongHit hit : second) {
            merged.putIfAbsent(hitKey(hit), hit);
        }
        return new ArrayList<>(merged.values());
    }

    private String hitKey(QqSongHit hit) {
        if (StringUtils.hasText(hit.songMid())) {
            return hit.songMid();
        }
        return hit.name() + "|" + hit.albumMid();
    }

    /** 自动模式：过滤 + 打分 + 按专辑去重。 */
    private List<CoverCandidateVO> collectFiltered(List<QqSongHit> hits, CoverSearchDTO dto, int limit) {
        String targetTitle = normalizer.normalizeTitle(dto.getSongName());
        String targetArtist = normalizer.normalizeArtist(dto.getArtistName());
        String targetAlbum = normalizer.normalizeAlbum(dto.getAlbum());

        List<Scored> scored = new ArrayList<>();
        for (QqSongHit hit : hits) {
            if (!hit.hasAlbumCover()) {
                // 没有 albumMid 就拼不出封面地址，直接跳过这个候选而不是整体失败
                continue;
            }
            if (!normalizer.normalizeTitle(hit.name()).equals(targetTitle)) {
                // 歌名必须完全相等（归一化后）：挡掉《稻香》《夜曲》《兰亭序》这类无关曲目
                continue;
            }
            if (targetArtist != null && StringUtils.hasText(hit.artist())
                    && !normalizer.artistMatches(hit.artist(), targetArtist)) {
                // 歌手必须有交集：挡掉别人的同名翻唱
                continue;
            }
            double score = score(hit, targetTitle, targetArtist, targetAlbum, dto.getDurationSeconds());
            scored.add(new Scored(hit, score));
        }

        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        return toCandidates(scored, limit);
    }

    /**
     * 手动模式：不做歌名/歌手过滤，但按<b>用户自己输入的关键词</b>排相关度。
     *
     * <p>为什么不干脆不过滤也不排序：实测搜「我很忙」会返回 A-Lin 的同名歌、2NE1 的
     * 《난 바빠》、《天若有情》等一堆无关专辑，而真正想要的周杰伦专辑《我很忙》
     * 排在第 2 位。8 个候选里只有 1 个是对的，排序又是随机的，等于让用户挨个试。
     *
     * <p>关键区别在于：这里用的是<b>用户刚输入的关键词</b>（可信）做相关度排序，
     * 而不是用<b>解析出来的元数据</b>（可能错）做过滤。前者只会让正确结果更靠前，
     * 不会把正确结果筛掉；后者才是要避免的。
     */
    private List<CoverCandidateVO> collectAll(List<QqSongHit> hits, String keyword, int limit) {
        String normalizedKeyword = normalizer.normalizeAlbum(keyword);
        String normalizedTitleKeyword = normalizer.normalizeTitle(keyword);
        List<Scored> scored = new ArrayList<>();
        for (QqSongHit hit : hits) {
            if (!hit.hasAlbumCover()) {
                continue;
            }
            scored.add(new Scored(hit, keywordRelevance(hit, normalizedKeyword, normalizedTitleKeyword)));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        return toCandidates(scored, limit);
    }

    /**
     * 关键词相关度：关键词与专辑名/歌名/歌手的吻合程度。
     *
     * <p>专辑名完全相等权重最高 —— 用户输「我很忙」时最可能就是想找那张专辑；
     * 其次是歌名完全相等；再次是包含关系。
     */
    private double keywordRelevance(QqSongHit hit, String keyword, String titleKeyword) {
        double score = 0d;
        String album = normalizer.normalizeAlbum(hit.albumName());
        String title = normalizer.normalizeTitle(hit.name());
        String artist = normalizer.normalizeArtist(hit.artist());

        if (!keyword.isEmpty() && !album.isEmpty()) {
            if (album.equals(keyword)) {
                score += 0.5;
            } else if (album.contains(keyword) || keyword.contains(album)) {
                score += 0.25;
            }
        }
        if (!titleKeyword.isEmpty() && !title.isEmpty()) {
            if (title.equals(titleKeyword)) {
                score += 0.35;
            } else if (title.contains(titleKeyword) || titleKeyword.contains(title)) {
                score += 0.15;
            }
        }
        if (!keyword.isEmpty() && !artist.isEmpty()
                && (artist.contains(keyword) || keyword.contains(artist))) {
            score += 0.2;
        }
        // 有专辑名的略优于没有的（没有专辑名通常意味着是翻唱或自制上传）
        if (StringUtils.hasText(hit.albumName())) {
            score += 0.05;
        }
        return score;
    }

    /**
     * 按专辑去重并转成候选。
     *
     * <p>去重是必须的：同一张专辑里的多首歌共用一张封面，实测「稻香」与「兰亭序」
     * 都指向《魔杰座》（同一个 albumMid）。不去重的话候选列表里会出现多张一模一样的图。
     *
     * <p>保留每张专辑里<b>得分最高</b>的那条作为代表（列表已排序，首次出现即最高）。
     */
    private List<CoverCandidateVO> toCandidates(List<Scored> scored, int limit) {
        Map<String, CoverCandidateVO> byAlbum = new LinkedHashMap<>();
        for (Scored item : scored) {
            QqSongHit hit = item.hit();
            if (byAlbum.containsKey(hit.albumMid())) {
                continue;
            }
            byAlbum.put(hit.albumMid(), toCandidate(hit, item.score()));
            if (byAlbum.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(byAlbum.values());
    }

    private CoverCandidateVO toCandidate(QqSongHit hit, double score) {
        CoverCandidateVO vo = new CoverCandidateVO();
        vo.setAlbumMid(hit.albumMid());
        vo.setAlbumName(hit.albumName());
        vo.setArtistName(hit.artist());
        vo.setTrackName(hit.name());
        vo.setVersionLabel(versionLabel(hit.name()));
        vo.setScore(round(score));
        vo.setDurationSeconds(hit.intervalSeconds());
        vo.setCoverUrls(QqCoverUrl.allSizes(hit.albumMid()));
        return vo;
    }

    /**
     * 打分：歌名相等是前提（已在过滤阶段保证），这里叠加专辑与时长两个维度。
     *
     * <p>时长能有效区分同一首歌的录音室版 / Live 版 —— 实测《青花瓷》录音室版 239 秒、
     * 魔天伦 Live 版 238 秒、中国好声音版 241 秒，彼此只差几秒，所以容差取 3 秒。
     */
    private double score(QqSongHit hit, String targetTitle, String targetArtist,
                         String targetAlbum, Double targetDuration) {
        double score = 0.6; // 歌名完全相等

        if (targetArtist != null && StringUtils.hasText(hit.artist())) {
            String artist = normalizer.normalizeArtist(hit.artist());
            if (artist != null && artist.equals(targetArtist)) {
                score += 0.3;
            } else if (artist != null && (artist.contains(targetArtist) || targetArtist.contains(artist))) {
                score += 0.15;
            }
        }

        if (targetAlbum != null && StringUtils.hasText(hit.albumName())
                && normalizer.normalizeAlbum(hit.albumName()).equals(targetAlbum)) {
            score += 0.1;
        }

        if (targetDuration != null && targetDuration > 0 && hit.intervalSeconds() != null
                && hit.intervalSeconds() > 0) {
            double delta = Math.abs(hit.intervalSeconds() - targetDuration);
            if (delta <= 3) {
                score += 0.1;
            } else if (delta <= 8) {
                score += 0.05;
            } else if (delta > 20) {
                // 时长差过大，几乎可以确定不是同一个版本
                score -= 0.2;
            }
        }
        return score;
    }

    /**
     * 版本标签。
     *
     * <p>同名不同版本在 QQ 音乐里普遍存在，不标注的话管理员只能靠专辑名猜。
     * 判断顺序按「最能说明问题」排：伴奏类最特殊，其次 Live、翻唱、Remix。
     */
    String versionLabel(String trackName) {
        if (!StringUtils.hasText(trackName)) {
            return "录音室";
        }
        String text = trackName.toLowerCase(Locale.ROOT);
        if (INSTRUMENTAL.matcher(text).find()) {
            return "伴奏";
        }
        if (LIVE.matcher(text).find()) {
            return "Live";
        }
        if (COVER.matcher(text).find()) {
            return "翻唱";
        }
        if (REMIX.matcher(text).find()) {
            return "混音";
        }
        return "录音室";
    }

    /** 统计过滤后可用（能拼出封面）的候选数，用于决定是否退化检索。 */
    private int countUsable(List<QqSongHit> hits, CoverSearchDTO dto) {
        String targetTitle = normalizer.normalizeTitle(dto.getSongName());
        String targetArtist = normalizer.normalizeArtist(dto.getArtistName());
        int count = 0;
        for (QqSongHit hit : hits) {
            if (!hit.hasAlbumCover()) {
                continue;
            }
            if (!normalizer.normalizeTitle(hit.name()).equals(targetTitle)) {
                continue;
            }
            if (targetArtist != null && StringUtils.hasText(hit.artist())
                    && !normalizer.artistMatches(hit.artist(), targetArtist)) {
                continue;
            }
            count++;
        }
        return count;
    }

    private int resolveLimit(Integer requested, boolean manual) {
        int fallback = manual ? MANUAL_LIMIT : AUTO_LIMIT;
        if (requested == null || requested <= 0) {
            return fallback;
        }
        return Math.min(requested, MAX_LIMIT);
    }

    private Double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }

    /** 打分中间态 */
    private record Scored(QqSongHit hit, double score) {
    }
}
