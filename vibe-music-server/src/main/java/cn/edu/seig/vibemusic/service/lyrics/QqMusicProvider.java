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
import java.util.ArrayList;
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
 */
@Slf4j
@Component
public class QqMusicProvider implements LyricsProvider {

    public static final String SOURCE = "QQ_MUSIC";

    private static final String SEARCH_PATH = "/soso/fcgi-bin/search_for_qq_cp";
    private static final String LYRIC_PATH = "/lyric/fcgi-bin/fcg_query_lyric_new.fcg";
    private static final String DEFAULT_BASE_URL = "https://c.y.qq.com";
    private static final String DEFAULT_REFERER = "https://y.qq.com/portal/player.html";
    private static final int SEARCH_LIMIT = 8;

    @Autowired
    private LyricsProperties properties;
    @Autowired
    private LyricsHttpClient http;
    @Autowired
    private LyricsMatcher matcher;
    @Autowired
    private ObjectMapper objectMapper;

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

        QqSong best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        // 依次尝试关键词：先「歌名 歌手」，若得分不足再退化为纯歌名。
        // 某些专辑（例如银临《山色有无中》）用组合关键词会返回专辑里的其它曲目、
        // 反而搜不到同名主打歌，因此必须按"得分"而不是"结果是否为空"来决定是否退化。
        for (String keyword : candidateKeywords(query)) {
            List<QqSong> hits = searchSongs(keyword);
            for (QqSong hit : hits) {
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

        String lyric = fetchLyric(best.mid());
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

    private List<QqSong> searchSongs(String keyword) {
        String url = baseUrl() + SEARCH_PATH
                + "?w=" + encode(keyword)
                + "&format=json&p=1&n=" + SEARCH_LIMIT + "&platform=yqq";

        String body = http.get(client, url, headers(), config.getReadTimeoutMs(), config.getMaxRetries(), SOURCE);
        if (!StringUtils.hasText(body)) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode list = root.path("data").path("song").path("list");
            if (!list.isArray()) {
                list = root.path("data").path("body").path("song").path("list");
            }
            if (!list.isArray()) {
                return List.of();
            }
            List<QqSong> songs = new ArrayList<>();
            for (JsonNode node : list) {
                String mid = firstText(node, "songmid", "mid");
                String name = firstText(node, "songname", "title");
                if (!StringUtils.hasText(mid) || !StringUtils.hasText(name)) {
                    continue;
                }
                // QQ 音乐用 mid="0" 表示该曲目没有歌词（伴奏、语音备忘录等），
                // 直接跳过，既避免无效请求，也防止它抢占"最佳匹配"导致漏配。
                if ("0".equals(mid.trim())) {
                    continue;
                }
                songs.add(new QqSong(
                        mid,
                        name,
                        joinSingers(node.path("singer")),
                        firstText(node, "albumname", "album"),
                        node.path("interval").isNumber() ? node.path("interval").asInt() : null
                ));
            }
            return songs;
        } catch (Exception e) {
            log.warn("解析 QQ音乐搜索结果失败：{}", e.toString());
            return List.of();
        }
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

    private String joinSingers(JsonNode singerNodes) {
        if (!singerNodes.isArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode singer : singerNodes) {
            String name = singer.path("name").asText("");
            if (!StringUtils.hasText(name)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('/');
            }
            builder.append(name);
        }
        return builder.toString();
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText(null);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", StringUtils.hasText(config.getUserAgent())
                ? config.getUserAgent() : "Mozilla/5.0");
        headers.put("Referer", StringUtils.hasText(config.getReferer())
                ? config.getReferer() : DEFAULT_REFERER);
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

    /** QQ 音乐搜索结果条目 */
    private record QqSong(String mid, String name, String artist, String album, Integer interval) {

        LyricsCandidate toCandidate(String lyric) {
            return new LyricsCandidate(name, artist, album, interval, lyric, SOURCE);
        }

    }

}
