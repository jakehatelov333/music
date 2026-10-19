package cn.edu.seig.vibemusic.service.lyrics;

import cn.edu.seig.vibemusic.config.LyricsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * QQ 音乐歌词源（主源）。
 * <p>
 * 该源的特点是<b>一次请求即可拿到歌词正文 + 词/曲/编曲等制作信息</b>，与 QQ 音乐客户端展示一致：
 * <pre>
 * [00:00.02]青花瓷 - 周杰伦
 * [00:05.49]词：方文山
 * [00:10.98]曲：周杰伦
 * [00:16.47]编曲：钟兴民
 * [00:21.97]素胚勾勒出青花笔锋浓转淡
 * </pre>
 * <p>
 * 调用的是 y.qq.com 网页播放器自用的内部 CGI 接口，需要携带 Referer 伪装来源，
 * 无需签名与登录。接口未公开且可能变更，因此已做超时、重试与失败降级处理。
 * <p>
 * 为避免无谓请求，先用元数据（歌名/歌手/时长）挑出最可能的一条，再拉取它的歌词。
 *
 * <h3>曲目检索为什么不在本类里</h3>
 * 搜索接口返回的字段同时被歌词链路和封面链路使用（后者需要 {@code albummid}）。
 * 检索与解析已抽到 {@link QqMusicCatalog}，本类只负责「挑一条最佳匹配 + 拉歌词」。
 * 这样两条链路共用同一份解析结果，不会出现只在某条链路上复现的字段偏差。
 */
@Slf4j
@Component
public class QqMusicProvider implements LyricsProvider {

    public static final String SOURCE = "QQ_MUSIC";

    private static final String LYRIC_PATH = "/lyric/fcgi-bin/fcg_query_lyric_new.fcg";
    private static final String DEFAULT_BASE_URL = "https://c.y.qq.com";

    @Autowired
    private LyricsProperties properties;
    @Autowired
    private LyricsHttpClient http;
    @Autowired
    private LyricsMatcher matcher;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private QqMusicCatalog catalog;

    private HttpClient client;
    private LyricsProperties.Provider config;

    @PostConstruct
    void init() {
        this.config = properties.getProviders().getQq();
        this.client = http.create(config, properties.getProxy());
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public boolean enabled() {
        return properties.isEnabled() && config != null && config.isEnabled();
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public List<LyricsCandidate> search(Query query) {
        if (!enabled() || query == null || !StringUtils.hasText(query.trackName())) {
            return List.of();
        }

        QqSongHit best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        // 依次尝试关键词：先「歌名 歌手」，若得分不足再退化为纯歌名。
        // 某些专辑（例如银临《山色有无中》）用组合关键词会返回专辑里的其它曲目、
        // 反而搜不到同名主打歌，因此必须按"得分"而不是"结果是否为空"来决定是否退化。
        for (String keyword : candidateKeywords(query)) {
            List<QqSongHit> hits = catalog.search(keyword, QqMusicCatalog.DEFAULT_LIMIT);
            for (QqSongHit hit : hits) {
                // QQ 音乐用 songmid="0" 表示该曲目没有歌词（伴奏、语音备忘录等），
                // 直接跳过，既避免无效请求，也防止它抢占"最佳匹配"导致漏配。
                if (hit.lyricsUnavailable()) {
                    continue;
                }
                double score = matcher.score(hit.toCandidate(null), query);
                if (score > bestScore) {
                    bestScore = score;
                    best = hit;
                }
            }
            if (bestScore >= properties.getMatch().getMinScore()) {
                break;
            }
        }

        if (best == null || bestScore < properties.getMatch().getMinScore()) {
            log.debug("QQ音乐未找到可信匹配，最高分 {}：{} - {}",
                    bestScore, query.artistName(), query.trackName());
            return List.of();
        }

        String lyric = fetchLyric(best.songMid());
        if (!StringUtils.hasText(lyric)) {
            return List.of();
        }
        return List.of(best.toCandidate(lyric));
    }

    /** 关键词尝试顺序：组合关键词优先，其次纯歌名（去重） */
    private List<String> candidateKeywords(Query query) {
        String track = query.trackName().trim();
        String combined = buildKeyword(query);
        if (combined.equals(track)) {
            return List.of(track);
        }
        return List.of(combined, track);
    }

    private String fetchLyric(String mid) {
        String url = baseUrl() + LYRIC_PATH
                + "?songmid=" + encode(mid)
                + "&format=json&nobase64=1&g_tk=5381";

        String body = http.get(client, url, headers(), config.getReadTimeoutMs(), config.getMaxRetries(), SOURCE);
        if (!StringUtils.hasText(body)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            return root.path("lyric").asText(null);
        } catch (Exception e) {
            log.warn("解析 QQ音乐歌词失败：{}", e.toString());
            return null;
        }
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", StringUtils.hasText(config.getUserAgent())
                ? config.getUserAgent() : "Mozilla/5.0");
        headers.put("Referer", StringUtils.hasText(config.getReferer())
                ? config.getReferer() : "https://y.qq.com/portal/player.html");
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        return headers;
    }

    private String buildKeyword(Query query) {
        String track = query.trackName().trim();
        if (StringUtils.hasText(query.artistName())) {
            return track + " " + query.artistName().trim();
        }
        return track;
    }

    private String baseUrl() {
        String base = config.getBaseUrl();
        if (!StringUtils.hasText(base)) {
            base = DEFAULT_BASE_URL;
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

}
