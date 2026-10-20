package cn.edu.seig.vibemusic.service.artist;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QQ 音乐歌手头像地址拼装。
 *
 * <p>钉住的是<b>安全边界</b>而不是字符串格式：这个类唯一的对外承诺是
 * 「给一个 mid，拼出一个固定 CDN 上的地址」，而它的价值在于
 * 「给任何别的输入，都不拼地址」。所以大部分用例都在验证拒绝。
 */
class QqArtistAvatarUrlTest {

    private static final String VALID_MID = "0025NhlN2yWrP4";

    // ------------------------------------------------------------------ 合法 mid

    @Test
    @DisplayName("合法 mid 拼出 500px 地址")
    void buildsDefaultSize() {
        assertEquals("https://y.gtimg.cn/music/photo_new/T001R500x500M0000025NhlN2yWrP4_1.jpg",
                QqArtistAvatarUrl.of(VALID_MID, 500));
    }

    @Test
    @DisplayName("三档尺寸都能拼，且尺寸出现在路径里")
    void buildsAllSizes() {
        Map<String, String> urls = QqArtistAvatarUrl.allSizes(VALID_MID, "11");

        assertEquals(List.of("300", "500", "800"), List.copyOf(urls.keySet()));
        assertTrue(urls.get("300").contains("T001R300x300"));
        assertTrue(urls.get("500").contains("T001R500x500"));
        assertTrue(urls.get("800").contains("T001R800x800"));
        assertTrue(urls.get("500").endsWith("_11.jpg"));
    }

    @Test
    @DisplayName("不支持的尺寸回退到 500")
    void unknownSizeFallsBackToDefault() {
        assertEquals(QqArtistAvatarUrl.DEFAULT_SIZE, QqArtistAvatarUrl.normalizeSize(1024));
        assertEquals(QqArtistAvatarUrl.DEFAULT_SIZE, QqArtistAvatarUrl.normalizeSize(0));
        assertEquals(QqArtistAvatarUrl.DEFAULT_SIZE, QqArtistAvatarUrl.normalizeSize(-1));
        assertEquals(QqArtistAvatarUrl.DEFAULT_SIZE, QqArtistAvatarUrl.normalizeSize(null));
        assertEquals(300, QqArtistAvatarUrl.normalizeSize(300));
    }

    @Test
    @DisplayName("序号为空时用默认值")
    void missingSeqUsesDefault() {
        assertEquals("1", QqArtistAvatarUrl.normalizeSeq(null));
        assertEquals("1", QqArtistAvatarUrl.normalizeSeq(""));
        assertEquals("1", QqArtistAvatarUrl.normalizeSeq("   "));
        assertEquals("42", QqArtistAvatarUrl.normalizeSeq("42"));
        assertEquals("42", QqArtistAvatarUrl.normalizeSeq(" 42 "));
    }

    @Test
    @DisplayName("非法序号回退到默认值，绝不拼进路径")
    void invalidSeqFallsBack() {
        // 这些都能改写 URL 结构或跳出路径
        assertEquals("1", QqArtistAvatarUrl.normalizeSeq("../../etc/passwd"));
        assertEquals("1", QqArtistAvatarUrl.normalizeSeq("11?x=1"));
        assertEquals("1", QqArtistAvatarUrl.normalizeSeq("11#frag"));
        assertEquals("1", QqArtistAvatarUrl.normalizeSeq("abc"));
        assertEquals("1", QqArtistAvatarUrl.normalizeSeq("12345"));
        assertFalse(QqArtistAvatarUrl.isValidSeq("../../x"));
        assertTrue(QqArtistAvatarUrl.isValidSeq(null));
        assertTrue(QqArtistAvatarUrl.isValidSeq("7"));
    }

    // ------------------------------------------------------------------ 拒绝

    @Test
    @DisplayName("非法 mid 一律拒绝，不做任何兜底")
    void rejectsInvalidMid() {
        assertFalse(QqArtistAvatarUrl.isValidSingerMid(null));
        assertFalse(QqArtistAvatarUrl.isValidSingerMid(""));
        assertFalse(QqArtistAvatarUrl.isValidSingerMid("   "));
        // 太短 / 太长
        assertFalse(QqArtistAvatarUrl.isValidSingerMid("abc"));
        assertFalse(QqArtistAvatarUrl.isValidSingerMid("a".repeat(21)));
        // 含路径或协议字符：这些是 SSRF 的入口
        assertFalse(QqArtistAvatarUrl.isValidSingerMid("../../etc/passwd"));
        assertFalse(QqArtistAvatarUrl.isValidSingerMid("http://evil.com/x"));
        assertFalse(QqArtistAvatarUrl.isValidSingerMid("abc?x=1"));
        assertFalse(QqArtistAvatarUrl.isValidSingerMid("abc#frag"));
        assertFalse(QqArtistAvatarUrl.isValidSingerMid("abc/def/ghi"));
        assertFalse(QqArtistAvatarUrl.isValidSingerMid("abc def ghi"));
        assertFalse(QqArtistAvatarUrl.isValidSingerMid("中文中文中文中文中文"));
        // 正好 10 位与 20 位都合法
        assertTrue(QqArtistAvatarUrl.isValidSingerMid("a".repeat(10)));
        assertTrue(QqArtistAvatarUrl.isValidSingerMid("a".repeat(20)));
    }

    @Test
    @DisplayName("拼地址时 mid 不合法直接抛异常，不返回可疑字符串")
    void ofThrowsOnInvalidMid() {
        assertThrows(IllegalArgumentException.class, () -> QqArtistAvatarUrl.of("abc", 500));
        assertThrows(IllegalArgumentException.class, () -> QqArtistAvatarUrl.of(null, 500));
        assertThrows(IllegalArgumentException.class, () -> QqArtistAvatarUrl.of("http://evil.com", 500));
    }

    @Test
    @DisplayName("非法 mid 时 allSizes 返回空 map，而不是抛异常")
    void allSizesReturnsEmptyOnInvalidMid() {
        // 这个方法会被列表渲染路径调用，抛异常会让整个搜索结果页挂掉
        assertTrue(QqArtistAvatarUrl.allSizes("abc", null).isEmpty());
    }

    // ------------------------------------------------------------------ profile 映射

    @Test
    @DisplayName("QQ type 映射：0/1/2 有对应，3 与未知一律 null")
    void mapsGender() {
        assertEquals(0, QqArtistProfile.mapGender(0));
        assertEquals(1, QqArtistProfile.mapGender(1));
        assertEquals(2, QqArtistProfile.mapGender(2));
        // type=3 实测只出现过 1 次，语义不明，不猜
        assertNull(QqArtistProfile.mapGender(3));
        assertNull(QqArtistProfile.mapGender(null));
        assertNull(QqArtistProfile.mapGender(99));
    }

    @Test
    @DisplayName("profile.hasAnyField 反映是否真有可填内容")
    void hasAnyFieldReflectsContent() {
        QqArtistProfile empty = new QqArtistProfile("0025NhlN2yWrP4", "x", null, null, null, null, null, null, 0, Map.of());
        assertFalse(empty.hasAnyField(), "全空时不应声称有内容");

        QqArtistProfile withBirth = new QqArtistProfile("0025NhlN2yWrP4", "x", null,
                LocalDate.of(1979, 1, 18), null, null, null, null, 0, Map.of());
        assertTrue(withBirth.hasAnyField());

        QqArtistProfile blankIntro = new QqArtistProfile("0025NhlN2yWrP4", "x", null, null,
                null, "   ", null, null, 0, Map.of());
        assertFalse(blankIntro.hasAnyField(), "纯空白简介不算有内容");
    }

    @Test
    @DisplayName("profile 的头像地址方法在 mid 非法时安全返回空")
    void profileAvatarIsSafeOnInvalidMid() {
        QqArtistProfile bad = new QqArtistProfile("abc", "x", null, null, null, null, null, null, 0, Map.of());
        assertNull(bad.avatarUrl());
        assertTrue(bad.avatarUrls().isEmpty());
    }

    @Test
    @DisplayName("wikiKeys 在 wikiFields 为 null 时返回空列表而不是抛异常")
    void wikiKeysHandlesNull() {
        QqArtistProfile profile = new QqArtistProfile("0025NhlN2yWrP4", "x", null, null,
                null, null, null, null, null, null);
        assertTrue(profile.wikiKeys().isEmpty());
    }

    @Test
    @DisplayName("wikiKeys 保留键顺序，便于前端稳定展示")
    void wikiKeysKeepsOrder() {
        Map<String, String> wiki = new LinkedHashMap<>();
        wiki.put("外文名", "Jay Chou");
        wiki.put("国籍", "中国");
        wiki.put("出生地", "台湾省新北市");
        QqArtistProfile profile = new QqArtistProfile("0025NhlN2yWrP4", "周杰伦", 0, null,
                "中国", "简介", "Jay Chou", "11", 11, wiki);

        assertEquals(List.of("外文名", "国籍", "出生地"), profile.wikiKeys());
    }
}
