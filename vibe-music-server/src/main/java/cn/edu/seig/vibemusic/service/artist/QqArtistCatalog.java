package cn.edu.seig.vibemusic.service.artist;

import cn.edu.seig.vibemusic.config.LyricsProperties;
import cn.edu.seig.vibemusic.service.lyrics.LyricsHttpClient;
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
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QQ 音乐歌手目录客户端：按名字搜歌手、按 mid 取详情。
 *
 * <h3>为什么不能复用 {@code QqMusicCatalog}</h3>
 * 那个组件打的是 {@code /soso/fcgi-bin/search_for_qq_cp}，即<b>搜歌</b>接口；
 * 它的 {@code singer} 字段只是歌曲的附属信息，没有歌手 mid，也没有头像。
 * 搜歌手必须用另一个接口 {@code /splcloud/fcgi-bin/smartbox_new.fcg}。
 * 两条链路的目标不同、返回结构不同，硬塞进一个类只会让两边都难以理解。
 *
 * <h3>两个接口</h3>
 * <pre>
 * 搜索  GET  https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?key=周杰伦&format=json&platform=yqq
 *            → data.singer.itemlist[] = { name, mid, pic }
 *
 * 详情  POST https://u.y.qq.com/cgi-bin/musicu.fcg
 *            {"comm":{"ct":24,"cv":0},
 *             "req":{"module":"music.musichallSinger.SingerInfoInter","method":"GetSingerDetail",
 *                    "param":{"singer_mids":[...],"ex_singer":1,"wiki_singer":1,"pic":1}}}
 *            → req.data.singer_list[] = { basic_info, ex_info, wiki, pic }
 * </pre>
 *
 * <h3>实测事实（决定了下面这些实现选择）</h3>
 * <ul>
 *   <li>详情接口<b>同时支持 GET</b>（{@code ?data={json}}），返回体与 POST 完全一致。
 *       因此可以直接复用 {@link LyricsHttpClient#get} 的超时/重试/代理，不必新增 POST 方法；</li>
 *   <li>批量一次传 20 个 mid 正常返回 20 条；不存在的 mid 会被<b>静默跳过</b>而不是报错，
 *       所以调用方必须按 mid 回查而不是按下标对应；</li>
 *   <li>全部 mid 都不存在时 {@code req.code = 0} 但 {@code singer_list} 为空；
 *       传空数组时 {@code req.code = 104400}。两种都当作"无结果"，不抛异常；</li>
 *   <li>响应体积差异极大：带 wiki+photos 的 20 条约 197 KB，只带 pic 的只要 8 KB。
 *       搜索页只需要名字和头像，详情页才要 wiki，所以两个方法分别控制这些开关。</li>
 * </ul>
 *
 * <h3>为什么复用 {@code lyrics.providers.qq} 的配置</h3>
 * 打的是同一个域名族（{@code c.y.qq.com} / {@code u.y.qq.com}）、同一套限流，
 * 复用同一份超时/重试/UA/代理配置才不会出现"歌词能抓、歌手抓不到"这种只在
 * 某条链路上复现的偏差。
 */
@Slf4j
@Component
public class QqArtistCatalog {

    private static final String SEARCH_PATH = "/splcloud/fcgi-bin/smartbox_new.fcg";
    private static final String DETAIL_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg";
    private static final String DEFAULT_BASE_URL = "https://c.y.qq.com";
    private static final String DEFAULT_REFERER = "https://y.qq.com/portal/player.html";

    /** 搜索召回条数。实测一个关键词通常只返回 1–2 个歌手，8 已足够覆盖同名变体。 */
    public static final int DEFAULT_LIMIT = 8;

    /** 单次搜索的召回上限。接口本身没有分页需求，8 之上再多也没有意义。 */
    public static final int MAX_LIMIT = 20;

    /** 详情接口单次最多传多少个 mid。实测 20 个正常返回 20 条。 */
    public static final int MAX_BATCH = 20;

    /**
     * wiki 里 {@code <key>k</key><value>v</value>} 的提取式。
     *
     * <p>用 CDATA 包裹是因为原始文本里的值可能含 {@code &}、{@code <} 等字符
     * （实测周杰伦的简介里有 {@code R&amp;B}，五月天的成就有 {@code <} 号）。
     * 刻意不用 XML 解析器：这段文本外层不是合法 XML 文档（只有一个 {@code <info>} 根、
     * 内部却有未转义的 {@code &}），交给解析器会直接抛异常。
     */
    private static final Pattern WIKI_ITEM = Pattern.compile(
            "<key><!\\[CDATA\\[(.*?)]]></key><value><!\\[CDATA\\[(.*?)]]></value>",
            Pattern.DOTALL);

    /** 从 {@code pic} 尾部抽出序号段，例如 {@code ..._11.jpg} → {@code 11}。 */
    private static final Pattern PIC_SEQ = Pattern.compile("_(\\d{1,3})\\.jpg$", Pattern.CASE_INSENSITIVE);

    /** wiki 里承载业务字段的键名。 */
    private static final String WIKI_COUNTRY = "国籍";
    private static final String WIKI_FOREIGN_NAME = "外文名";

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

    /**
     * 是否可用（总开关 + 该源开关）。
     *
     * <p>刻意对 {@code properties} / {@code config} 做空值判断：这个方法在每次调用前都会被
     * 问到，如果它自己抛空指针，那"优雅降级"就无从谈起。构造未完成（单元测试里只 new 了对象、
     * 没跑 {@code @PostConstruct}）时按"不可用"处理，让调用方拿到空结果而不是异常。
     */
    public boolean enabled() {
        return properties != null && config != null && properties.isEnabled() && config.isEnabled();
    }

    /** 按默认条数搜索歌手。 */
    public List<QqArtistHit> search(String keyword) {
        return search(keyword, DEFAULT_LIMIT);
    }

    /**
     * 按关键词搜索歌手。
     *
     * @param keyword 搜索关键词，通常是歌手名
     * @param limit   召回条数，会被夹到 {@code [1, MAX_LIMIT]}
     * @return 命中列表；未启用、参数为空或请求失败时返回空列表（不抛异常）
     */
    public List<QqArtistHit> search(String keyword, int limit) {
        if (!enabled() || !StringUtils.hasText(keyword)) {
            return List.of();
        }
        int pageSize = Math.max(1, Math.min(limit, MAX_LIMIT));
        String url = baseUrl() + SEARCH_PATH
                + "?key=" + encode(keyword.trim())
                + "&format=json&platform=yqq";

        String body = http.get(client, url, headers(), config.getReadTimeoutMs(), config.getMaxRetries(),
                "QQ_ARTIST");
        if (!StringUtils.hasText(body)) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root.path("code").asInt(-1) != 0) {
                log.debug("QQ音乐歌手搜索返回非零 code：{}", root.path("code").asText());
                return List.of();
            }
            JsonNode list = root.path("data").path("singer").path("itemlist");
            if (!list.isArray()) {
                return List.of();
            }
            List<QqArtistHit> hits = new ArrayList<>();
            for (JsonNode node : list) {
                String mid = trimToNull(node.path("mid").asText(null));
                String name = trimToNull(node.path("name").asText(null));
                if (mid == null || name == null) {
                    continue;
                }
                QqArtistHit hit = new QqArtistHit(mid, name, extractSeq(node.path("pic").asText(null)));
                if (hit.usable()) {
                    hits.add(hit);
                }
            }
            return hits;
        } catch (Exception e) {
            log.warn("解析 QQ音乐歌手搜索结果失败：{}", e.toString());
            return List.of();
        }
    }

    /**
     * 按 mid 取歌手详情。
     *
     * @param singerMid 歌手 mid
     * @return 详情；未启用、mid 不合法、请求失败或对方无此歌手时返回 {@code null}
     */
    public QqArtistProfile detail(String singerMid) {
        if (!QqArtistAvatarUrl.isValidSingerMid(singerMid)) {
            return null;
        }
        List<QqArtistProfile> profiles = details(List.of(singerMid));
        return profiles.isEmpty() ? null : profiles.get(0);
    }

    /**
     * 批量取详情。
     *
     * <p>返回顺序不保证与入参一致 —— 接口会静默跳过不存在的 mid，调用方必须按
     * {@link QqArtistProfile#singerMid()} 回查。超过 {@link #MAX_BATCH} 的入参会被截断。
     *
     * @return 详情列表；无有效结果时返回空列表（不抛异常）
     */
    public List<QqArtistProfile> details(List<String> singerMids) {
        if (!enabled() || singerMids == null || singerMids.isEmpty()) {
            return List.of();
        }
        List<String> valid = singerMids.stream()
                .filter(QqArtistAvatarUrl::isValidSingerMid)
                .distinct()
                .limit(MAX_BATCH)
                .toList();
        if (valid.isEmpty()) {
            return List.of();
        }

        String payload = buildDetailPayload(valid);
        // 详情接口同时支持 GET（?data={json}），复用已有的 GET 客户端即可拿到超时/重试/代理
        String url = DETAIL_URL + "?data=" + encode(payload) + "&format=json";
        String body = http.get(client, url, headers(), config.getReadTimeoutMs(), config.getMaxRetries(),
                "QQ_ARTIST");
        if (!StringUtils.hasText(body)) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            int reqCode = root.path("req").path("code").asInt(-1);
            if (reqCode != 0) {
                log.debug("QQ音乐歌手详情返回非零 code：{}", reqCode);
                return List.of();
            }
            JsonNode list = root.path("req").path("data").path("singer_list");
            if (!list.isArray()) {
                return List.of();
            }
            List<QqArtistProfile> profiles = new ArrayList<>();
            for (JsonNode node : list) {
                QqArtistProfile profile = parseProfile(node);
                if (profile != null) {
                    profiles.add(profile);
                }
            }
            return profiles;
        } catch (Exception e) {
            log.warn("解析 QQ音乐歌手详情失败：{}", e.toString());
            return List.of();
        }
    }

    /** 拼详情接口的 JSON 请求体（作为 GET 的 {@code data} 参数）。 */
    String buildDetailPayload(List<String> singerMids) {
        StringBuilder mids = new StringBuilder();
        for (String mid : singerMids) {
            if (mids.length() > 0) {
                mids.append(',');
            }
            mids.append('"').append(mid).append('"');
        }
        // ex_singer 提供 ex_info（简介/生日），wiki_singer 提供 wiki（国籍/外文名），
        // group_singer 提供乐队成员。photos 刻意关掉：那是十几张大图地址，
        // 一次 20 条会让响应从 8 KB 涨到 197 KB，而我们只需要一张主头像。
        return "{\"comm\":{\"ct\":24,\"cv\":0},"
                + "\"req\":{\"module\":\"music.musichallSinger.SingerInfoInter\","
                + "\"method\":\"GetSingerDetail\","
                + "\"param\":{\"singer_mids\":[" + mids + "],"
                + "\"ex_singer\":1,\"wiki_singer\":1,\"group_singer\":1,\"pic\":1,\"photos\":0}}}";
    }

    private QqArtistProfile parseProfile(JsonNode node) {
        JsonNode basic = node.path("basic_info");
        String mid = trimToNull(basic.path("singer_mid").asText(null));
        String name = trimToNull(basic.path("name").asText(null));
        if (mid == null || name == null) {
            return null;
        }

        JsonNode exInfo = node.path("ex_info");
        String seq = extractSeq(node.path("pic").path("pic").asText(null));
        Map<String, String> wiki = parseWiki(node.path("wiki").asText(null));

        return new QqArtistProfile(
                mid,
                name,
                QqArtistProfile.mapGender(basic.path("type").isNumber() ? basic.path("type").asInt() : null),
                parseBirth(exInfo.path("birthday").asText(null)),
                // 国籍只能从 wiki 取：ex_info.area 是 0~4 的分区码，不是国家
                blankToNull(wiki.get(WIKI_COUNTRY)),
                blankToNull(exInfo.path("desc").asText(null)),
                blankToNull(wiki.get(WIKI_FOREIGN_NAME)),
                seq,
                // has_photo 名字像布尔值，实测是照片张数（周杰伦 11、邓紫棋 7、冷门音乐人 3），
                // 0/缺失才表示没有照片
                basic.path("has_photo").isNumber() ? basic.path("has_photo").asInt() : null,
                wiki);
    }

    /**
     * 解析 wiki 里的键值对。
     *
     * <p>返回空 map 而不是 {@code null}：调用方大量使用 {@code wiki.get(...)}，
     * 少一次判空就少一个空指针。
     */
    Map<String, String> parseWiki(String xml) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (!StringUtils.hasText(xml)) {
            return fields;
        }
        Matcher matcher = WIKI_ITEM.matcher(xml);
        while (matcher.find()) {
            String key = matcher.group(1).trim();
            String value = matcher.group(2).trim();
            // 同名键保留第一次出现的值：QQ 的 wiki 里同一字段偶尔重复，
            // 首个通常是最规范的写法
            if (!key.isEmpty() && !value.isEmpty()) {
                fields.putIfAbsent(key, value);
            }
        }
        return fields;
    }

    /**
     * 解析生日。
     *
     * <p>只用结构化的 {@code ex_info.birthday}（格式 {@code 1979-01-18}），
     * 不去猜 wiki 里那条 {@code 1979年1月18日（农历腊月二十）} —— 括号里是农历，
     * 硬解析容易把农历日期当成公历写进库。
     */
    LocalDate parseBirth(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            log.debug("QQ音乐生日格式无法解析，已忽略：{}", value);
            return null;
        }
    }

    /** 从头像地址尾部抽序号，例如 {@code ...M0000025NhlN2yWrP4_11.jpg} → {@code 11}。 */
    String extractSeq(String picUrl) {
        if (!StringUtils.hasText(picUrl)) {
            return null;
        }
        Matcher matcher = PIC_SEQ.matcher(picUrl.trim());
        return matcher.find() ? matcher.group(1) : null;
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
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
