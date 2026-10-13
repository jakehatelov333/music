package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.config.LyricsProperties;
import cn.edu.seig.vibemusic.model.entity.Song;
import cn.edu.seig.vibemusic.model.vo.ArtistNameVO;
import cn.edu.seig.vibemusic.service.lyrics.LyricsCandidate;
import cn.edu.seig.vibemusic.service.lyrics.LyricsMatcher;
import cn.edu.seig.vibemusic.service.lyrics.LyricsProvider;
import cn.edu.seig.vibemusic.service.lyrics.LyricsTextCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 歌词检索的歌手名回退链（P6）。
 *
 * <p>背景：{@code LyricsMatcher} 的归一化会清掉 {@code /} 与空格，而歌词源返回的
 * 合作歌曲歌手串是「银临/Aki阿杰」。因此传主艺人名「银临」只能拿到「包含关系」（+0.15），
 * 传完整展示串「银临 / Aki阿杰」才可能相等（+0.3）。本测试用假数据源把这条链路固定下来，
 * 并断言请求次数受 {@code max-artist-attempts} 约束 —— 否则批量补全会成倍放大请求量。
 */
class LyricsArtistFallbackTest {

    private LyricsServiceImpl service;
    private LyricsProperties properties;

    @BeforeEach
    void setUp() {
        properties = new LyricsProperties();
        LyricsMatcher matcher = new LyricsMatcher();
        ReflectionTestUtils.setField(matcher, "properties", properties);

        service = new LyricsServiceImpl();
        ReflectionTestUtils.setField(service, "properties", properties);
        ReflectionTestUtils.setField(service, "matcher", matcher);
        ReflectionTestUtils.setField(service, "cleaner", new LyricsTextCleaner());
    }

    // ------------------------------------------------------------------ 候选链规则

    @Test
    void candidatesFollowPrimaryThenDisplayThenCollaborators() {
        List<ArtistNameVO> cooperation = List.of(artist(143, "银临"), artist(145, "Aki阿杰"));

        assertEquals(List.of("银临", "银临 / Aki阿杰"),
                LyricsServiceImpl.buildArtistCandidates("银临", cooperation, 2, false));
        assertEquals(List.of("银临", "银临 / Aki阿杰", "Aki阿杰"),
                LyricsServiceImpl.buildArtistCandidates("银临", cooperation, 3, true));
        // 上限优先于候选数量
        assertEquals(List.of("银临"),
                LyricsServiceImpl.buildArtistCandidates("银临", cooperation, 1, true));
        // 单歌手：展示串与主艺人名相同 → 去重后只剩一个候选，不会产生多余请求
        assertEquals(List.of("银临"),
                LyricsServiceImpl.buildArtistCandidates("银临", List.of(artist(143, "银临")), 2, true));
        // 关系缺失（历史数据）→ 退化为主艺人名
        assertEquals(List.of("银临"),
                LyricsServiceImpl.buildArtistCandidates("银临", List.of(), 2, true));
        // 主艺人名缺失 → 仍能用展示串
        assertEquals(List.of("银临 / Aki阿杰"),
                LyricsServiceImpl.buildArtistCandidates(null, cooperation, 2, false));
    }

    // ------------------------------------------------------------------ 回退链实跑（假数据源）

    @Test
    void retriesWithFullDisplayStringWhenPrimaryArtistScoresBelowThreshold() {
        // 数据源只返回歌手串「Aki阿杰」：用主艺人名「银临」打分只有 0.6，低于阈值 0.7
        FakeProvider qq = new FakeProvider("QQ_MUSIC", 10)
                .respond("银临 / Aki阿杰", "Aki阿杰");
        useProviders(qq);

        var fetched = service.fetchFromProviders(song(), cooperationContext(), null);

        assertEquals(List.of("银临", "银临 / Aki阿杰"), qq.queried);
        assertTrue(fetched.matched());
        // 0.6（歌名精确）+ 0.15（歌手包含）= 0.75，恰好过阈值 —— 余量只有 0.05，正是需要回退链的原因
        assertEquals(0.75, fetched.score(), 1e-9);
        // 展示用歌手名是完整展示串，与检索用的歌手名分离
        assertTrue(fetched.lyric().contains("[ar:银临 / Aki阿杰]"));
    }

    @Test
    void singleArtistSongAsksTheProviderOnlyOnce() {
        FakeProvider qq = new FakeProvider("QQ_MUSIC", 10).respond("银临", "银临");
        useProviders(qq);

        var fetched = service.fetchFromProviders(song(),
                new LyricsServiceImpl.ArtistContext("银临", "银临", List.of("银临")), null);

        assertEquals(List.of("银临"), qq.queried);
        assertTrue(fetched.matched());
        assertEquals(0.9, fetched.score(), 1e-9);
    }

    @Test
    void providersAreExhaustedWithThePrimaryNameBeforeEscalating() {
        FakeProvider qq = new FakeProvider("QQ_MUSIC", 10);   // 主源对两个候选都无结果
        FakeProvider lrclib = new FakeProvider("LRCLIB", 20).respond("银临 / Aki阿杰", "Aki阿杰");
        useProviders(qq, lrclib);

        var fetched = service.fetchFromProviders(song(), cooperationContext(), null);

        // 先穷尽数据源（QQ → LRCLIB）再换下一个歌手名候选，避免为了试名字而跳过优先级更高的源
        assertEquals(List.of("银临", "银临 / Aki阿杰"), qq.queried);
        assertEquals(List.of("银临", "银临 / Aki阿杰"), lrclib.queried);
        assertTrue(fetched.matched());
        assertEquals("LRCLIB", fetched.source());
    }

    @Test
    void maxArtistAttemptsOneDisablesTheFallback() {
        properties.getMatch().setMaxArtistAttempts(1);
        FakeProvider qq = new FakeProvider("QQ_MUSIC", 10)
                .respond("银临 / Aki阿杰", "Aki阿杰");
        useProviders(qq);

        List<String> candidates = LyricsServiceImpl.buildArtistCandidates(
                "银临", List.of(artist(143, "银临"), artist(145, "Aki阿杰")), 1, false);
        var fetched = service.fetchFromProviders(song(),
                new LyricsServiceImpl.ArtistContext("银临", "银临 / Aki阿杰", candidates), null);

        assertEquals(List.of("银临"), qq.queried);
        assertFalse(fetched.matched());
    }

    // ------------------------------------------------------------------ 辅助

    private static LyricsServiceImpl.ArtistContext cooperationContext() {
        List<ArtistNameVO> relations = List.of(artist(143, "银临"), artist(145, "Aki阿杰"));
        return new LyricsServiceImpl.ArtistContext("银临", "银临 / Aki阿杰",
                LyricsServiceImpl.buildArtistCandidates("银临", relations, 2, false));
    }

    private void useProviders(LyricsProvider... providers) {
        ReflectionTestUtils.setField(service, "providers", List.of(providers));
    }

    private static Song song() {
        // 不设 duration：避免时长加分把 0.6 抬到阈值，掩盖回退链是否真的生效
        return new Song().setSongId(622L).setSongName("牵丝戏").setArtistId(143L);
    }

    private static ArtistNameVO artist(long id, String name) {
        ArtistNameVO vo = new ArtistNameVO();
        vo.setArtistId(id);
        vo.setArtistName(name);
        return vo;
    }

    /** 假数据源：按「被问到的歌手名」决定返回什么候选，并记录访问顺序。 */
    private static final class FakeProvider implements LyricsProvider {

        private final String source;
        private final int order;
        private final Map<String, LyricsCandidate> responses = new LinkedHashMap<>();
        private final List<String> queried = new ArrayList<>();

        FakeProvider(String source, int order) {
            this.source = source;
            this.order = order;
        }

        FakeProvider respond(String queriedArtist, String candidateArtist) {
            responses.put(queriedArtist,
                    new LyricsCandidate("牵丝戏", candidateArtist, null, null, "[00:01.00]戏腔起", source));
            return this;
        }

        @Override
        public String source() {
            return source;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public List<LyricsCandidate> search(Query query) {
            queried.add(query.artistName());
            LyricsCandidate candidate = responses.get(query.artistName());
            return candidate == null ? List.of() : List.of(candidate);
        }
    }
}
