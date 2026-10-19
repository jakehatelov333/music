package cn.edu.seig.vibemusic.service.cover;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 封面地址拼装与 albumMid 校验。
 *
 * <p>重点是<b>安全边界</b>：这个端点只接受 albumMid、不接受 URL，因为接受 URL 就等于
 * 给管理端开一个「让服务端抓任意地址」的入口（SSRF）。因此 albumMid 的格式校验不是
 * 防御性编程的摆设，而是这条边界的实际守卫 —— 必须证明它挡得住路径穿越、协议注入
 * 与任意域名。
 */
class QqCoverUrlTest {

    private static final String VALID_MID = "002eFUFm2XYZ7z";

    @Test
    @DisplayName("按模板拼出三档地址，尺寸可切换")
    void buildsAllSizes() {
        Map<String, String> urls = QqCoverUrl.allSizes(VALID_MID);

        assertEquals(3, urls.size());
        assertEquals("https://y.gtimg.cn/music/photo_new/T002R300x300M000002eFUFm2XYZ7z.jpg", urls.get("300"));
        assertEquals("https://y.gtimg.cn/music/photo_new/T002R500x500M000002eFUFm2XYZ7z.jpg", urls.get("500"));
        assertEquals("https://y.gtimg.cn/music/photo_new/T002R800x800M000002eFUFm2XYZ7z.jpg", urls.get("800"));
    }

    @Test
    @DisplayName("合法 albumMid 通过校验")
    void acceptsValidAlbumMid() {
        assertTrue(QqCoverUrl.isValidAlbumMid(VALID_MID));
        assertTrue(QqCoverUrl.isValidAlbumMid("004c8Md02WHHju"));
        assertTrue(QqCoverUrl.isValidAlbumMid("000zuqG92V3O6w"));
    }

    /**
     * 路径穿越、协议注入、任意域名都必须被挡掉。
     *
     * <p>这些输入如果能通过校验，服务端就会去抓调用方指定的地址 —— 正是要避免的 SSRF。
     */
    @Test
    @DisplayName("路径穿越 / 协议注入 / 任意域名 / 超短串一律拒绝")
    void rejectsUnsafeAlbumMid() {
        String[] unsafe = {
                "../../etc/passwd",
                "abc/def/../../x",
                "http://evil.com/x",
                "https://evil.com/x.jpg",
                "002eFUFm2XYZ7z?x=1",
                "002eFUFm2XYZ7z#frag",
                "002eFUFm2XYZ7z/../../other",
                "abc",                    // 太短
                "0000000000000000000000", // 太长（22 位）
                "",                       // 空
                "   ",
                "002eFUFm2XYZ7!",         // 非法字符
                "002e FUFm2XYZ7z",        // 含空格
                null
        };
        for (String mid : unsafe) {
            assertFalse(QqCoverUrl.isValidAlbumMid(mid), "应拒绝：" + mid);
        }
    }

    @Test
    @DisplayName("非法 albumMid 拼地址时抛异常，调用方须先校验")
    void throwsOnInvalidAlbumMid() {
        assertThrows(IllegalArgumentException.class, () -> QqCoverUrl.of("abc", 500));
        assertThrows(IllegalArgumentException.class, () -> QqCoverUrl.of("../x", 500));
    }

    @Test
    @DisplayName("尺寸非法时回退到默认 500")
    void normalizesSize() {
        assertEquals(500, QqCoverUrl.normalizeSize(null));
        assertEquals(500, QqCoverUrl.normalizeSize(999));
        assertEquals(500, QqCoverUrl.normalizeSize(-1));
        assertEquals(300, QqCoverUrl.normalizeSize(300));
        assertEquals(800, QqCoverUrl.normalizeSize(800));
    }

    @Test
    @DisplayName("非法尺寸不会拼出畸形地址，而是回退到 500")
    void invalidSizeFallsBackInUrl() {
        String url = QqCoverUrl.of(VALID_MID, 12345);
        assertTrue(url.contains("T002R500x500"), "实际：" + url);
    }
}
