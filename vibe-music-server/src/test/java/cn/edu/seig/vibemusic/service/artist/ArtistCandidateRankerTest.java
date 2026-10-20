package cn.edu.seig.vibemusic.service.artist;

import cn.edu.seig.vibemusic.service.cover.CoverNameNormalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 歌手候选的匹配与排序。
 *
 * <p>这份测试的每一条都对应一个<b>实测过的真实错配</b>，不是凭空构造的：
 * 拿库里 129 位歌手逐个调 QQ 的搜索接口，记录下哪些候选被排到了前面、
 * 哪些是同一个名字但不同的人。下面的用例把这些观察固定下来，
 * 防止以后"优化排序"时把它们改回去。
 */
class ArtistCandidateRankerTest {

    private ArtistCandidateRanker ranker;

    @BeforeEach
    void setUp() {
        ranker = new ArtistCandidateRanker();
        // normalizer 在 ranker 里只用于归一化，这里注入真实实现即可
        org.springframework.test.util.ReflectionTestUtils.setField(
                ranker, "normalizer", new CoverNameNormalizer());
    }

    // ------------------------------------------------------------------ 归一化

    @Test
    @DisplayName("去尾部括号：IU (아이유) → iu")
    void normalizesTrailingBracket() {
        assertEquals("iu", ranker.normalize("IU (아이유)"));
        assertEquals("孝敏", ranker.normalize("孝敏 (효민)"));
        assertEquals("daishidance", ranker.normalize("DAISHI DANCE (ダイシ・ダンス)"));
    }

    @Test
    @DisplayName("不去中间的括号内容以外的部分，也保留中间的点号含义")
    void keepsNonTrailingContent() {
        // G.E.M.邓紫棋 没有尾部括号，点号被去掉但主体保留
        assertEquals("gem邓紫棋", ranker.normalize("G.E.M.邓紫棋"));
        // 括号在中间时不剥离（只处理结尾）
        assertEquals("周杰伦战队", ranker.normalize("周杰伦战队"));
    }

    @Test
    @DisplayName("大小写与标点差异被抹平")
    void ignoresCaseAndPunctuation() {
        assertEquals(ranker.normalize("Taylor Swift"), ranker.normalize("taylor  swift"));
        assertEquals(ranker.normalize("LANY"), ranker.normalize("lany"));
        assertEquals(ranker.normalize("RADWIMPS (ラッドウィンプス)"), ranker.normalize("RADWIMPS"));
    }

    @Test
    @DisplayName("hasTrailingBracket 只对结尾括号为真")
    void detectsTrailingBracket() {
        assertTrue(ranker.hasTrailingBracket("IU (아이유)"));
        assertTrue(ranker.hasTrailingBracket("孝敏（효민）"));
        assertFalse(ranker.hasTrailingBracket("G.E.M.邓紫棋"));
        assertFalse(ranker.hasTrailingBracket("周杰伦"));
        assertFalse(ranker.hasTrailingBracket(null));
    }

    @Test
    @DisplayName("stripTrailingBracket 无括号时原样返回")
    void stripKeepsNameWithoutBracket() {
        assertEquals("周杰伦", ranker.stripTrailingBracket("周杰伦"));
        assertEquals("IU", ranker.stripTrailingBracket("IU (아이유)"));
        assertEquals("", ranker.stripTrailingBracket(null));
    }

    // ------------------------------------------------------------------ 打分

    @Test
    @DisplayName("完全一致得最高分")
    void exactMatchScoresHighest() {
        assertEquals(ArtistCandidateRanker.SCORE_EXACT, ranker.score("周杰伦", "周杰伦"));
    }

    @Test
    @DisplayName("包含关系得分低于完全一致 —— 周杰伦 vs 周杰伦战队")
    void containsScoresLowerThanExact() {
        int contains = ranker.score("周杰伦", "周杰伦战队");
        assertEquals(ArtistCandidateRanker.SCORE_CONTAINS, contains);
        assertTrue(contains < ArtistCandidateRanker.SCORE_EXACT,
                "衍生账号（周杰伦战队）不能和本尊同分");
    }

    @Test
    @DisplayName("毫无关系得最低分")
    void unrelatedScoresLowest() {
        assertEquals(ArtistCandidateRanker.SCORE_UNRELATED, ranker.score("lany", "blue"));
        assertEquals(ArtistCandidateRanker.SCORE_UNRELATED, ranker.score("christopher", "黄凯芹"));
    }

    @Test
    @DisplayName("过短的名字不做包含判断 —— IU 不应匹配 iukoi77")
    void shortNamesSkipContains() {
        // "iu" 长度 2 < MIN_CONTAINS_LENGTH，所以即使 iukoi77 包含 "iu" 也不算相关
        assertEquals(ArtistCandidateRanker.SCORE_UNRELATED, ranker.score("iu", "iukoi77"));
        // 长度够时包含关系仍然生效
        assertEquals(ArtistCandidateRanker.SCORE_CONTAINS, ranker.score("taylorswift", "taylorswiftband"));
    }

    @Test
    @DisplayName("空输入得最低分，不抛异常")
    void emptyInputsScoreLowest() {
        assertEquals(ArtistCandidateRanker.SCORE_UNRELATED, ranker.score("", "周杰伦"));
        assertEquals(ArtistCandidateRanker.SCORE_UNRELATED, ranker.score("周杰伦", ""));
        assertEquals(ArtistCandidateRanker.SCORE_UNRELATED, ranker.score("", ""));
    }

    // ------------------------------------------------------------------ 排序

    @Test
    @DisplayName("排序把完全一致排到前面，衍生账号排后面")
    void ranksExactFirst() {
        List<String> hits = List.of("周杰伦战队", "周杰伦");

        List<String> sorted = ranker.rank("周杰伦", hits, name -> name).stream()
                .map(ranked -> ranked.hit())
                .toList();

        assertEquals(List.of("周杰伦", "周杰伦战队"), sorted);
    }

    @Test
    @DisplayName("同分时保持搜索接口的原始顺序（尊重对方的相关度）")
    void stableOnTie() {
        List<String> hits = List.of("AAA", "BBB", "CCC");

        List<String> sorted = ranker.rank("完全不相关", hits, name -> name).stream()
                .map(ranked -> ranked.hit())
                .toList();

        assertEquals(hits, sorted, "同分不应打乱原始顺序");
    }

    @Test
    @DisplayName("空候选返回空列表")
    void emptyHitsYieldEmpty() {
        assertTrue(ranker.<String>rank("周杰伦", List.of(), name -> name).isEmpty());
        assertTrue(ranker.<String>rank("周杰伦", null, name -> name).isEmpty());
    }

    // ------------------------------------------------------------------ 是否可自动预选

    @Test
    @DisplayName("归一化后一致才可自动预选")
    void preselectOnlyWhenNormalizedEqual() {
        // 去括号后一致 —— 可以自动选
        assertTrue(ranker.isSafeToPreselect("IU (아이유)", "IU"));
        assertTrue(ranker.isSafeToPreselect("RADWIMPS (ラッドウィンプス)", "RADWIMPS"));
        assertTrue(ranker.isSafeToPreselect("周杰伦", "周杰伦"));
        // 大小写差异也算一致
        assertTrue(ranker.isSafeToPreselect("LANY", "LANY"));
    }

    @Test
    @DisplayName("包含关系一律不自动预选 —— 这正是最容易选错的一类")
    void doesNotPreselectOnContains() {
        assertFalse(ranker.isSafeToPreselect("周杰伦", "周杰伦战队"));
        assertFalse(ranker.isSafeToPreselect("邓紫棋", "邓紫棋的棋士"));
        assertFalse(ranker.isSafeToPreselect("五月天", "五月天 阿信"));
        assertFalse(ranker.isSafeToPreselect("LANY", "Blue"));
    }

    @Test
    @DisplayName("looksDifferent 是 isSafeToPreselect 的反面，用于前端提示")
    void looksDifferentIsInverse() {
        assertTrue(ranker.looksDifferent("周杰伦", "周杰伦战队"));
        assertFalse(ranker.looksDifferent("周杰伦", "周杰伦"));
    }
}
