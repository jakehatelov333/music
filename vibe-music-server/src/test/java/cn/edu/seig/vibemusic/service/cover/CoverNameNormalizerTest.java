package cn.edu.seig.vibemusic.service.cover;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 歌名 / 歌手 / 专辑的归一化。
 *
 * <p>这份测试回答一个关键问题：<b>为什么不能复用 {@code LyricsMatcher} 的归一化</b>。
 * 那边把文本压成「只剩字母数字」（{@code 青花瓷Live}），适合打分，但用它判断
 * 「是不是同一首歌」会把 {@code (Live)} 与 {@code (戏腔版)} 的差别一起抹平。
 * 封面需要的是「去掉版本后缀后仍然相等」以及「歌手集合是否有交集」。
 */
class CoverNameNormalizerTest {

    private final CoverNameNormalizer normalizer = new CoverNameNormalizer();

    // ------------------------------------------------------------------ 歌名

    @Test
    @DisplayName("版本后缀被剥离，不同版本归到同一个歌名")
    void stripsVersionSuffix() {
        assertEquals("青花瓷", normalizer.normalizeTitle("青花瓷"));
        assertEquals("青花瓷", normalizer.normalizeTitle("青花瓷 (Live)"));
        assertEquals("青花瓷", normalizer.normalizeTitle("青花瓷（现场版）"));
        assertEquals("青花瓷", normalizer.normalizeTitle("青花瓷 - Live"));
        assertEquals("青花瓷", normalizer.normalizeTitle("青花瓷 (伴奏)"));
        assertEquals("青花瓷", normalizer.normalizeTitle("青花瓷 (戏腔版)"));
        assertEquals("青花瓷", normalizer.normalizeTitle("青花瓷 (DJ版)"));
        assertEquals("青花瓷", normalizer.normalizeTitle("青花瓷 (钢琴版)"));
    }

    /** 多段后缀要反复剥离，不能只去一层。 */
    @Test
    @DisplayName("多段版本后缀反复剥离")
    void stripsNestedSuffixes() {
        assertEquals("青花瓷", normalizer.normalizeTitle("青花瓷 - Live - 片段"));
        assertEquals("青花瓷", normalizer.normalizeTitle("青花瓷 (Live) (片段)"));
    }

    /** 不同的歌必须归一化到不同结果，否则过滤形同虚设。 */
    @Test
    @DisplayName("不同的歌归一化后不相等")
    void keepsDifferentTitlesDistinct() {
        assertNotEquals(normalizer.normalizeTitle("青花瓷"), normalizer.normalizeTitle("稻香"));
        assertNotEquals(normalizer.normalizeTitle("青花瓷"), normalizer.normalizeTitle("兰亭序"));
        // 「青花瓷」与「青花」是不同的歌，不能被包含关系吞掉
        assertNotEquals(normalizer.normalizeTitle("青花瓷"), normalizer.normalizeTitle("青花"));
    }

    @Test
    @DisplayName("feat. 及其之后的内容被剥离")
    void stripsFeat() {
        assertEquals("mylove", normalizer.normalizeTitle("My Love feat. Someone"));
        assertEquals("mylove", normalizer.normalizeTitle("My Love ft. Someone"));
    }

    @Test
    @DisplayName("大小写与标点不影响相等判定")
    void ignoresCaseAndPunctuation() {
        assertEquals(normalizer.normalizeTitle("Hello, World!"), normalizer.normalizeTitle("hello world"));
    }

    @Test
    @DisplayName("空白输入返回空串，不抛异常")
    void toleratesBlank() {
        assertEquals("", normalizer.normalizeTitle(null));
        assertEquals("", normalizer.normalizeTitle(""));
        assertEquals("", normalizer.normalizeTitle("   "));
    }

    // ------------------------------------------------------------------ 歌手

    @Test
    @DisplayName("歌手分隔符差异不影响匹配：/ 与 、 与空格等价")
    void artistSeparatorsAreEquivalent() {
        assertTrue(normalizer.artistMatches("银临/Aki阿杰", "银临 / Aki阿杰"));
        assertTrue(normalizer.artistMatches("银临、Aki阿杰", "银临/Aki阿杰"));
        assertTrue(normalizer.artistMatches("银临 & Aki阿杰", "银临/Aki阿杰"));
    }

    @Test
    @DisplayName("歌手顺序不同仍匹配")
    void artistOrderDoesNotMatter() {
        assertTrue(normalizer.artistMatches("Aki阿杰/银临", "银临/Aki阿杰"));
    }

    /** 候选是超集（合唱版）时仍算匹配，否则会漏掉合法的合唱封面。 */
    @Test
    @DisplayName("候选歌手是本地歌手的超集时算匹配（合唱版）")
    void supersetArtistMatches() {
        assertTrue(normalizer.artistMatches("周杰伦/江源东", "周杰伦"));
    }

    /** 完全不相干的歌手必须不匹配，否则别人的翻唱会被当成目标。 */
    @Test
    @DisplayName("无交集的歌手不匹配")
    void disjointArtistDoesNotMatch() {
        assertFalse(normalizer.artistMatches("某网络歌手", "陈奕迅"));
        assertFalse(normalizer.artistMatches("中国好声音学员", "周杰伦"));
    }

    @Test
    @DisplayName("任一方为空时视为匹配（不做歌手维度的裁剪）")
    void blankArtistMatchesAnything() {
        assertTrue(normalizer.artistMatches(null, "周杰伦"));
        assertTrue(normalizer.artistMatches("周杰伦", null));
        assertTrue(normalizer.artistMatches("", ""));
    }

    // ------------------------------------------------------------------ 专辑

    @Test
    @DisplayName("专辑名只去标点空白，不做版本剥离")
    void albumKeepsFullText() {
        assertEquals("我很忙", normalizer.normalizeAlbum("我很忙"));
        assertEquals(normalizer.normalizeAlbum("I'm Busy"), normalizer.normalizeAlbum("i m busy"));
        // 专辑名里的括号内容要保留：「我很忙（Im Busy）」不等于「我很忙」
        assertNotEquals(normalizer.normalizeAlbum("我很忙"), normalizer.normalizeAlbum("我很忙（Im Busy）"));
    }
}
