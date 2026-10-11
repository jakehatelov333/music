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
 * LRCLIB 歌词源（兜底）。
 * <p>
 * LRCLIB 是公开的开放接口，返回结构清晰且自带时间轴，但<b>不提供词曲作者信息</b>，
 * 因此仅作为 QQ 音乐无结果时的补充（主要覆盖欧美曲目）。它偶尔返回 503，需要退避重试。
 * <p>
 * 接口文档：https://lrclib.net/docs —— 要求携带可识别的 User-Agent。
 */
@Slf4j
@Component
public class LrcLibProvider implements LyricsProvider {

    public static final String SOURCE = "LRCLIB";

    private static final String SEARCH_PATH = "/api/search";
    private static final String DEFAULT_BASE_URL = "https://lrclib.net";

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
        this.config = properties.getProviders().getLrclib();
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
        return 20;
    }

    @Override
    public List<LyricsCandidate> search(Query query) {
        if (!enabled() || query == null || !StringUtils.hasText(query.trackName())) {
            return List.of();
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("track_name", query.trackName().trim());
        if (StringUtils.hasText(query.artistName())) {
            params.put("artist_name", query.artistName().trim());
        }
        if (StringUtils.hasText(query.albumName())) {
            params.put("album_name", query.albumName().trim());
        }

        List<LyricsCandidate> candidates = fetch(params);
        if (candidates.isEmpty()) {
            // 精确条件无结果时退化为全文检索
            Map<String, String> loose = new LinkedHashMap<>();
            loose.put("q", (query.trackName() + " " + (query.artistName() == null ? "" : query.artistName())).trim());
            candidates = fetch(loose);
        }
        return candidates;
    }

    private List<LyricsCandidate> fetch(Map<String, String> params) {
        String url = baseUrl() + SEARCH_PATH + "?" + queryString(params);
        String body = http.get(client, url, headers(), config.getReadTimeoutMs(), config.getMaxRetries(), SOURCE);
        if (!StringUtils.hasText(body)) {
            return List.of();
        }
        try {
            JsonNode array = objectMapper.readTree(body);
            if (!array.isArray()) {
                return List.of();
            }
            List<LyricsCandidate> candidates = new ArrayList<>();
            for (JsonNode node : array) {
                // 纯音乐没有歌词，直接跳过
                if (node.path("instrumental").asBoolean(false)) {
                    continue;
                }
                String synced = node.path("syncedLyrics").asText(null);
                String plain = node.path("plainLyrics").asText(null);
                String lyric = StringUtils.hasText(synced) ? synced : plain;
                if (!StringUtils.hasText(lyric)) {
                    continue;
                }
                Integer duration = node.path("duration").isNumber()
                        ? (int) Math.round(node.path("duration").asDouble()) : null;
                candidates.add(new LyricsCandidate(
                        node.path("trackName").asText(null),
                        node.path("artistName").asText(null),
                        node.path("albumName").asText(null),
                        duration,
                        lyric,
                        SOURCE
                ));
            }
            return candidates;
        } catch (Exception e) {
            log.warn("解析 LRCLIB 搜索结果失败：{}", e.toString());
            return List.of();
        }
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", StringUtils.hasText(config.getUserAgent())
                ? config.getUserAgent() : "VibeMusic/1.0");
        headers.put("Accept", "application/json");
        return headers;
    }

    private String queryString(Map<String, String> params) {
        StringBuilder builder = new StringBuilder();
        params.forEach((key, value) -> {
            if (!StringUtils.hasText(value)) {
                return;
            }
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(key).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        return builder.toString();
    }

    private String baseUrl() {
        String base = config.getBaseUrl();
        if (!StringUtils.hasText(base)) {
            base = DEFAULT_BASE_URL;
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

}
