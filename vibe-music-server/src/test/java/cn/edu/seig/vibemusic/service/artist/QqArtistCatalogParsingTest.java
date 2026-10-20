package cn.edu.seig.vibemusic.service.artist;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QQ 音乐歌手响应的解析。
 *
 * <p>这些用例的输入全部取自<b>真实响应</b>（周杰伦、五月天、Michael Cramtu 等），
 * 而不是按文档想象的格式 —— 该接口的坑几乎都在真实数据的边角里：
 * wiki 是 XML 片段但不是合法 XML、生日在 wiki 里带农历括号、
 * {@code ex_info.area} 是分区码而不是国家、乐队没有结构化生日。
 */
class QqArtistCatalogParsingTest {

    private QqArtistCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new QqArtistCatalog();
    }

    // ------------------------------------------------------------------ wiki 解析

    @Test
    @DisplayName("从真实 wiki 片段里抽出国籍/外文名/出生地")
    void parsesRealWikiFragment() {
        // 摘自周杰伦的真实响应，保留了 CDATA 与转义
        String wiki = "<info><id>4558</id><basic>"
                + "<item><key><![CDATA[外文名]]></key><value><![CDATA[Jay Chou]]></value></item>"
                + "<item><key><![CDATA[别名]]></key><value><![CDATA[周董]]></value></item>"
                + "<item><key><![CDATA[国籍]]></key><value><![CDATA[中国]]></value></item>"
                + "<item><key><![CDATA[出生地]]></key><value><![CDATA[台湾省新北市]]></value></item>"
                + "<item><key><![CDATA[生日]]></key><value><![CDATA[1979年1月18日（农历腊月二十）]]></value></item>"
                + "</basic></info>";

        Map<String, String> fields = catalog.parseWiki(wiki);

        assertEquals("Jay Chou", fields.get("外文名"));
        assertEquals("周董", fields.get("别名"));
        assertEquals("中国", fields.get("国籍"));
        assertEquals("台湾省新北市", fields.get("出生地"));
        assertEquals("1979年1月18日（农历腊月二十）", fields.get("生日"));
    }

    @Test
    @DisplayName("wiki 为空或 null 时返回空 map，不是 null")
    void emptyWikiYieldsEmptyMap() {
        assertTrue(catalog.parseWiki(null).isEmpty());
        assertTrue(catalog.parseWiki("").isEmpty());
        assertTrue(catalog.parseWiki("   ").isEmpty());
        // 有内容但没有 key/value 结构
        assertTrue(catalog.parseWiki("<info><id>4558</id></info>").isEmpty());
    }

    @Test
    @DisplayName("同名键只保留第一次出现的值")
    void keepsFirstValueForDuplicateKey() {
        String wiki = "<item><key><![CDATA[国籍]]></key><value><![CDATA[中国]]></value></item>"
                + "<item><key><![CDATA[国籍]]></key><value><![CDATA[美国]]></value></item>";

        assertEquals("中国", catalog.parseWiki(wiki).get("国籍"));
    }

    @Test
    @DisplayName("值里含 & 和 < 时仍能解析（不用 XML 解析器的理由）")
    void handlesSpecialCharacters() {
        // 真实数据里周杰伦的简介含 R&B，五月天的成就含 < 号
        String wiki = "<item><key><![CDATA[风格]]></key><value><![CDATA[R&B / Pop]]></value></item>"
                + "<item><key><![CDATA[成就]]></key><value><![CDATA[销量 < 百万]]></value></item>";

        Map<String, String> fields = catalog.parseWiki(wiki);

        assertEquals("R&B / Pop", fields.get("风格"));
        assertEquals("销量 < 百万", fields.get("成就"));
    }

    @Test
    @DisplayName("空 key 或空 value 被跳过")
    void skipsEmptyKeyOrValue() {
        String wiki = "<item><key><![CDATA[]]></key><value><![CDATA[x]]></value></item>"
                + "<item><key><![CDATA[y]]></key><value><![CDATA[]]></value></item>"
                + "<item><key><![CDATA[国籍]]></key><value><![CDATA[中国]]></value></item>";

        Map<String, String> fields = catalog.parseWiki(wiki);

        assertEquals(1, fields.size());
        assertEquals("中国", fields.get("国籍"));
    }

    // ------------------------------------------------------------------ 生日

    @Test
    @DisplayName("结构化的生日能解析")
    void parsesStructuredBirth() {
        assertEquals(LocalDate.of(1979, 1, 18), catalog.parseBirth("1979-01-18"));
        assertEquals(LocalDate.of(1989, 5, 30), catalog.parseBirth("1989-05-30"));
        assertEquals(LocalDate.of(1756, 1, 27), catalog.parseBirth("1756-01-27"));
        assertEquals(LocalDate.of(1993, 5, 16), catalog.parseBirth("1993-05-16"));
    }

    @Test
    @DisplayName("空生日返回 null")
    void emptyBirthIsNull() {
        assertNull(catalog.parseBirth(null));
        assertNull(catalog.parseBirth(""));
        assertNull(catalog.parseBirth("   "));
    }

    @Test
    @DisplayName("格式异常返回 null 而不是抛异常 —— 乐队常常没有结构化生日")
    void malformedBirthIsNull() {
        // 五月天的 ex_info.birthday 实测就是空串；这里额外覆盖各种脏数据
        assertNull(catalog.parseBirth("1979年1月18日"));
        assertNull(catalog.parseBirth("unknown"));
        assertNull(catalog.parseBirth("1979-13-45"));
        assertNull(catalog.parseBirth("1979/01/18"));
    }

    // ------------------------------------------------------------------ 头像序号

    @Test
    @DisplayName("从头像地址尾部抽序号")
    void extractsSeq() {
        assertEquals("11", catalog.extractSeq(
                "http://y.gtimg.cn/music/photo_new/T001R150x150M0000025NhlN2yWrP4_11.jpg"));
        assertEquals("7", catalog.extractSeq(
                "http://y.gtimg.cn/music/photo_new/T001R150x150M000001fNHEf1SFEFN_7.jpg"));
        assertEquals("5", catalog.extractSeq(
                "http://y.gtimg.cn/music/photo_new/T001R150x150M000000Sp0Bz4JXH0o_5.jpg"));
    }

    @Test
    @DisplayName("没有序号段时返回 null，交给默认值兜底")
    void missingSeqIsNull() {
        assertNull(catalog.extractSeq(null));
        assertNull(catalog.extractSeq(""));
        assertNull(catalog.extractSeq("http://y.gtimg.cn/music/photo_new/T001R150x150M0000025NhlN2yWrP4.jpg"));
        // 后缀不是 .jpg
        assertNull(catalog.extractSeq("http://y.gtimg.cn/x_11.png"));
    }

    // ------------------------------------------------------------------ 请求体

    @Test
    @DisplayName("详情请求体包含全部 mid，且 photos 关闭（体积从 197KB 降到 8KB）")
    void buildsDetailPayload() {
        String payload = catalog.buildDetailPayload(List.of("0025NhlN2yWrP4", "000qrPik2w6lDr"));

        assertTrue(payload.contains("\"singer_mids\":[\"0025NhlN2yWrP4\",\"000qrPik2w6lDr\"]"),
                "应包含两个 mid：" + payload);
        assertTrue(payload.contains("\"ex_singer\":1"), "需要 ex_info 才有简介和生日");
        assertTrue(payload.contains("\"wiki_singer\":1"), "需要 wiki 才有国籍");
        assertTrue(payload.contains("\"photos\":0"), "photos 是十几张大图，必须关掉");
        assertTrue(payload.contains("GetSingerDetail"));
        assertTrue(payload.contains("music.musichallSinger.SingerInfoInter"));
    }

    @Test
    @DisplayName("单个 mid 时数组里只有一个元素，没有多余逗号")
    void buildsPayloadForSingleMid() {
        String payload = catalog.buildDetailPayload(List.of("0025NhlN2yWrP4"));

        assertTrue(payload.contains("[\"0025NhlN2yWrP4\"]"), "不应有尾随逗号：" + payload);
    }

    // ------------------------------------------------------------------ 未启用时短路

    @Test
    @DisplayName("未启用时搜索与详情都返回空，不发起任何请求")
    void disabledCatalogShortCircuits() {
        // setUp 里没注入 properties，config 为 null → enabled() 为 false
        assertTrue(catalog.search("周杰伦").isEmpty());
        assertTrue(catalog.detail("0025NhlN2yWrP4") == null);
        assertTrue(catalog.details(List.of("0025NhlN2yWrP4")).isEmpty());
    }

    @Test
    @DisplayName("非法 mid 不进入网络请求")
    void invalidMidShortCircuits() {
        assertNull(catalog.detail("abc"));
        assertNull(catalog.detail("http://evil.com/x"));
        assertNull(catalog.detail(null));
        assertTrue(catalog.details(List.of("abc", "../../etc/passwd")).isEmpty());
    }
}
