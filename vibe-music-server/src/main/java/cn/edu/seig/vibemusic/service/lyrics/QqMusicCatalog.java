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
 * QQ 音乐曲目检索客户端 —— 歌词链路与封面链路共用同一个搜索接口。
 *
 * <h3>为什么单独抽出来</h3>
 * 搜索接口（{@code /soso/fcgi-bin/search_for_qq_cp}）返回的是一条曲目的<b>全部</b>元数据：
 * 曲目 mid、曲名、歌手、专辑名、专辑 mid、时长。歌词只需要 {@code songMid}，
 * 封面只需要 {@code albumMid}。历史实现把 {@code albumMid} 解析出来又丢掉，
 * 于是「有搜索结果却拼不出封面」。
 *
 * <p>把搜索独立成一个组件后：两条链路拿到的是同一份解析结果，不会出现
 * 「歌词搜得到、封面搜不到」这种只在某条链路上复现的偏差；
 * 未来接口字段变更也只需要改这一处。
 *
 * <h3>为什么仍然放在 {@code service/lyrics} 包</h3>
 * 它复用的是歌词子系统已有的 HTTP 支撑（超时、重试、可选代理）与同一份
 * {@code lyrics.providers.qq} 配置。搬到独立包就得再复制一套超时/重试逻辑，
 * 而这两条链路打的是同一个域名、同一套限流。
 */
@Slf4j
@Component
public class QqMusicCatalog {

    private static final String SEARCH_PATH = "/soso/fcgi-bin/search_for_qq_cp";
    private static final String DEFAULT_BASE_URL = "https://c.y.qq.com";
    private static final String DEFAULT_REFERER = "https://y.qq.com/portal/player.html";

    /**
     * 歌词链路的默认召回条数。
     *
     * <p>保持 8 条不变：歌词只挑一条最佳匹配，召回变宽只会增加无谓解析，
     * 而且会让「先组合关键词、再退化纯歌名」的两段式策略更容易被低分候选干扰。
     */
    public static final int DEFAULT_LIMIT = 8;

    /**
     * 单页召回上限。
     *
     * <p>实测该接口 {@code n=20} 正常返回 20 条；手动搜封面时召回越宽越好
     * （用户可能只记得专辑名或只记得歌名），因此封面链路用满 20。
     * 不设更大值：接口对超大 {@code n} 的行为未经验证，且 20 条已足够覆盖同名不同版本。
     */
    public static final int MAX_LIMIT = 20;

    @Autowired
    private LyricsProperties properties;
    @Autowired
    private LyricsHttpClient http;
    @Autowired
    private ObjectMapper objectMapper;

    private HttpClient client;
    private LyricsProperties.Provider config;

    @PostConstruct
    void init() {
        this.config = properties.getProviders().getQq();
        this.client = http.create(config, properties.getProxy());
    }

    /** 是否可用（总开关 + 该源开关）。 */
    public boolean enabled() {
        return properties.isEnabled() && config != null && config.isEnabled();
    }

    /** 按默认召回条数搜索。 */
    public List<QqSongHit> search(String keyword) {
        return search(keyword, DEFAULT_LIMIT);
    }

    /**
     * 按关键词搜索曲目。
     *
     * @param keyword 搜索关键词，可以是「歌名 歌手」组合、纯歌名，或专辑名
     * @param limit   召回条数，会被夹到 {@code [1, MAX_LIMIT]}
     * @return 命中列表；未启用、参数为空或请求失败时返回空列表（不抛异常）
     */
    public List<QqSongHit> search(String keyword, int limit) {
        if (!enabled() || !StringUtils.hasText(keyword)) {
            return List.of();
        }
        int pageSize = Math.max(1, Math.min(limit, MAX_LIMIT));
        String url = baseUrl() + SEARCH_PATH
                + "?w=" + encode(keyword.trim())
                + "&format=json&p=1&n=" + pageSize + "&platform=yqq";

        String body = http.get(client, url, headers(), config.getReadTimeoutMs(), config.getMaxRetries(),
                QqMusicProvider.SOURCE);
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
            List<QqSongHit> hits = new ArrayList<>();
            for (JsonNode node : list) {
                String songMid = firstText(node, "songmid", "mid");
                String name = firstText(node, "songname", "title");
                if (!StringUtils.hasText(name)) {
                    continue;
                }
                // songMid="0" 表示该曲目没有歌词（伴奏、语音备忘录等）。
                // 仍然保留在结果里：封面链路不关心有没有歌词，只关心 albumMid。
                hits.add(new QqSongHit(
                        songMid == null ? null : songMid.trim(),
                        name,
                        joinSingers(node.path("singer")),
                        firstText(node, "albumname", "album"),
                        trimToNull(firstText(node, "albummid", "albumMid")),
                        node.path("interval").isNumber() ? node.path("interval").asInt() : null
                ));
            }
            return hits;
        } catch (Exception e) {
            log.warn("解析 QQ音乐搜索结果失败：{}", e.toString());
            return List.of();
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

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
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
