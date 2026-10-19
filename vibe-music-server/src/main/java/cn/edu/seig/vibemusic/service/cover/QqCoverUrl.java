package cn.edu.seig.vibemusic.service.cover;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * QQ 音乐专辑封面地址拼装与校验。
 *
 * <p>封面地址由专辑 mid 唯一决定，形如：
 * <pre>https://y.gtimg.cn/music/photo_new/T002R{size}x{size}M000{albumMid}.jpg</pre>
 * 实测 300 / 500 / 800 三档均可用（约 34 KB / 78 KB / 171 KB），且<b>无防盗链</b>：
 * 不带 Referer 也返回 200，浏览器可以直接 {@code <img>} 加载，不需要后端代理。
 *
 * <h3>为什么只接受 albumMid，不接受 URL</h3>
 * 如果接口允许调用方传入任意 URL，管理端就多了一个"让服务端去抓任意地址"的入口
 * （SSRF）：内网地址、云元数据服务、任意协议都会被尝试。这里改成服务端按
 * {@code albumMid} 自行拼地址，mid 再用严格白名单校验，抓取目标就被限制在
 * 单个 CDN 的固定路径模板上。实测非法 mid（{@code abc}、{@code ZZZ...}）该 CDN
 * 一律返回 404，所以格式校验本身就是有效防线。
 */
public final class QqCoverUrl {

    private static final String TEMPLATE =
            "https://y.gtimg.cn/music/photo_new/T002R%sx%sM000%s.jpg";

    /**
     * 专辑 mid 的白名单。
     *
     * <p>QQ 音乐的 mid 是 14 位 Base62（字母 + 数字），实测样本如
     * {@code 002eFUFm2XYZ7z}、{@code 004c8Md02WHHju}。这里放宽到 10–20 位并只允许
     * 字母数字：既容忍将来长度变化，又不可能包含 {@code /}、{@code .}、{@code :}
     * 这类能改变 URL 结构的字符。
     */
    private static final Pattern ALBUM_MID = Pattern.compile("^[A-Za-z0-9]{10,20}$");

    /** 允许的封面尺寸。刻意只保留这三档：都是实测可用的正方形图。 */
    public static final List<Integer> SUPPORTED_SIZES = List.of(300, 500, 800);

    /** 默认尺寸。500 在清晰度与体积之间最平衡（约 78 KB）。 */
    public static final int DEFAULT_SIZE = 500;

    private QqCoverUrl() {
    }

    /** albumMid 是否合法（非空、纯字母数字、长度在 10–20 之间）。 */
    public static boolean isValidAlbumMid(String albumMid) {
        return albumMid != null && ALBUM_MID.matcher(albumMid).matches();
    }

    /** 把任意尺寸夹到受支持的取值；非法输入回退到 {@link #DEFAULT_SIZE}。 */
    public static int normalizeSize(Integer size) {
        if (size == null || !SUPPORTED_SIZES.contains(size)) {
            return DEFAULT_SIZE;
        }
        return size;
    }

    /**
     * 拼出指定尺寸的封面地址。
     *
     * @throws IllegalArgumentException albumMid 不合法（调用方应先校验并跳过该候选）
     */
    public static String of(String albumMid, int size) {
        if (!isValidAlbumMid(albumMid)) {
            throw new IllegalArgumentException("专辑 mid 不合法: " + albumMid);
        }
        int normalized = normalizeSize(size);
        return String.format(TEMPLATE, normalized, normalized, albumMid);
    }

    /** 一次给出全部受支持尺寸，避免前端切换尺寸时再打后端。 */
    public static Map<String, String> allSizes(String albumMid) {
        Map<String, String> urls = new LinkedHashMap<>();
        for (Integer size : SUPPORTED_SIZES) {
            urls.put(String.valueOf(size), of(albumMid, size));
        }
        return urls;
    }

}
