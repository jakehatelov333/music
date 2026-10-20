package cn.edu.seig.vibemusic.service.artist;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * QQ 音乐歌手头像地址拼装与校验。
 *
 * <p>与专辑封面（{@link cn.edu.seig.vibemusic.service.cover.QqCoverUrl}）同构，
 * 但路径模板不同：
 * <pre>https://y.gtimg.cn/music/photo_new/T001R{size}x{size}M000{singerMid}_{seq}.jpg</pre>
 *
 * <h3>实测结论</h3>
 * <ul>
 *   <li>300 / 500 / 800 三档均可用，分别约 18–38 KB / 43–86 KB / 96–183 KB；</li>
 *   <li><b>无防盗链</b>：空 Referer、{@code y.qq.com}、任意第三方 Referer 都返回 200
 *       且字节数完全相同，因此不需要后端代理，浏览器可直接加载；</li>
 *   <li>尾部 {@code _{seq}} 段可以省略或改成任意数字，CDN 一律返回同一张主图
 *       （实测 {@code _11} / {@code _1} / {@code _0} / {@code _99} 都是 60009 字节）。
 *       这里仍然保留它：搜索接口给出的 {@code pic} 带具体序号，原样沿用最不容易出错。</li>
 * </ul>
 *
 * <h3>为什么只接受 singerMid，不接受 URL</h3>
 * 与封面同一条理由：允许调用方传任意 URL 就等于给管理端开了一个 SSRF 入口。
 * 服务端按 mid 自行拼地址，mid 再用严格白名单校验，抓取目标就被限制在
 * 单个 CDN 的固定路径模板上。
 */
public final class QqArtistAvatarUrl {

    private static final String TEMPLATE =
            "https://y.gtimg.cn/music/photo_new/T001R%sx%sM000%s_%s.jpg";

    /**
     * 歌手 mid 的白名单。
     *
     * <p>与专辑 mid 同样是 14 位 Base62（实测 {@code 0025NhlN2yWrP4}、{@code 001fNHEf1SFEFN}）。
     * 放宽到 10–20 位只允许字母数字：容忍长度变化，同时不可能包含 {@code /}、{@code .}、
     * {@code :} 这类能改变 URL 结构的字符。
     */
    private static final Pattern SINGER_MID = Pattern.compile("^[A-Za-z0-9]{10,20}$");

    /** 头像序号段的白名单：只允许 1–3 位数字，避免把任意字符串拼进路径。 */
    private static final Pattern SEQ = Pattern.compile("^[0-9]{1,3}$");

    /** 序号缺省值。取 1 是因为实测 {@code _1} 与 {@code _11} 返回同一张主图。 */
    public static final String DEFAULT_SEQ = "1";

    /** 允许的头像尺寸。三档都是实测可用的正方形图。 */
    public static final List<Integer> SUPPORTED_SIZES = List.of(300, 500, 800);

    /**
     * 默认尺寸。
     *
     * <p>与封面一致取 500：歌手头像在管理端列表里只显示 64×64，在用户端详情页最大约
     * 200×200，500 已经足够清晰，而 800 的体积接近翻倍。
     */
    public static final int DEFAULT_SIZE = 500;

    private QqArtistAvatarUrl() {
    }

    /** singerMid 是否合法（非空、纯字母数字、长度在 10–20 之间）。 */
    public static boolean isValidSingerMid(String singerMid) {
        return singerMid != null && SINGER_MID.matcher(singerMid).matches();
    }

    /** 序号是否合法；空白视为使用默认值。 */
    public static boolean isValidSeq(String seq) {
        return seq == null || seq.isBlank() || SEQ.matcher(seq.trim()).matches();
    }

    /** 把任意尺寸夹到受支持的取值；非法输入回退到 {@link #DEFAULT_SIZE}。 */
    public static int normalizeSize(Integer size) {
        if (size == null || !SUPPORTED_SIZES.contains(size)) {
            return DEFAULT_SIZE;
        }
        return size;
    }

    /** 把任意序号规整成白名单内的形态；非法输入回退到 {@link #DEFAULT_SEQ}。 */
    public static String normalizeSeq(String seq) {
        if (!isValidSeq(seq)) {
            return DEFAULT_SEQ;
        }
        return seq == null || seq.isBlank() ? DEFAULT_SEQ : seq.trim();
    }

    /**
     * 拼出指定尺寸的头像地址。
     *
     * @throws IllegalArgumentException singerMid 不合法（调用方应先校验并跳过该候选）
     */
    public static String of(String singerMid, int size, String seq) {
        if (!isValidSingerMid(singerMid)) {
            throw new IllegalArgumentException("歌手 mid 不合法: " + singerMid);
        }
        int normalizedSize = normalizeSize(size);
        return String.format(TEMPLATE, normalizedSize, normalizedSize,
                singerMid, normalizeSeq(seq));
    }

    /** 用默认序号拼地址。 */
    public static String of(String singerMid, int size) {
        return of(singerMid, size, DEFAULT_SEQ);
    }

    /**
     * 一次给出全部受支持尺寸，避免前端切换尺寸时再打后端。
     *
     * <p>mid 不合法时返回<b>空 map</b>而不是抛异常：这个方法在渲染候选列表时被逐个调用，
     * 一条脏数据抛异常会让整页搜索结果都打不开。而 {@link #of} 保持抛异常 ——
     * 那是"我要一个确定可用的地址"的场景，静默返回错误字符串更糟。
     */
    public static Map<String, String> allSizes(String singerMid, String seq) {
        if (!isValidSingerMid(singerMid)) {
            return Map.of();
        }
        Map<String, String> urls = new LinkedHashMap<>();
        for (Integer size : SUPPORTED_SIZES) {
            urls.put(String.valueOf(size), of(singerMid, size, seq));
        }
        return urls;
    }

}
