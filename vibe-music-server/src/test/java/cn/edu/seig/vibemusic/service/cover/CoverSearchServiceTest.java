package cn.edu.seig.vibemusic.service.cover;

import cn.edu.seig.vibemusic.model.dto.CoverSearchDTO;
import cn.edu.seig.vibemusic.model.vo.CoverCandidateVO;
import cn.edu.seig.vibemusic.service.lyrics.QqMusicCatalog;
import cn.edu.seig.vibemusic.service.lyrics.QqSongHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 封面候选的过滤、去重与两种检索模式。
 *
 * <p>这份测试的核心是把「不能直接把搜索结果当候选」这条结论钉死。实测数据：
 * 搜「淘金小镇 周杰伦」返回 8 条，只有 1 条是目标歌曲，其余是《稻香》《夜曲》
 * 《兰亭序》—— 如果不做过滤，管理员看到的候选里 7/8 是周杰伦别的专辑封面，
 * 而且从缩略图上根本看不出来选错了。
 */
class CoverSearchServiceTest {

    private CoverSearchService service;
    private FakeCatalog catalog;
    private CoverNameNormalizer normalizer;

    @BeforeEach
    void setUp() {
        catalog = new FakeCatalog();
        normalizer = new CoverNameNormalizer();
        service = new CoverSearchService();
        ReflectionTestUtils.setField(service, "catalog", catalog);
        ReflectionTestUtils.setField(service, "normalizer", normalizer);
    }

    // ------------------------------------------------------------------ 自动模式：过滤

    /**
     * 歌名不相等的条目必须被丢弃。
     *
     * <p>用的是真实实测数据：搜「青花瓷 周杰伦」返回 8 条，其中《七里香》《稻香》
     * 《一路向北》《兰亭序》都是别的歌，必须全部挡掉，只留下 3 个青花瓷版本。
     */
    @Test
    @DisplayName("自动模式只保留同名歌曲，挡掉同一搜索结果里的无关曲目")
    void autoModeKeepsOnlySameTitle() {
        catalog.respond("青花瓷 周杰伦", List.of(
                hit("青花瓷", "周杰伦", "我很忙", "002eFUFm2XYZ7z", 239),
                hit("青花瓷", "周杰伦", null, null, 233),           // 无专辑 mid，应被跳过
                hit("青花瓷 (Live)", "周杰伦", "周杰伦魔天伦世界巡回演唱会", "004c8Md02WHHju", 238),
                hit("青花瓷 (Live)", "周杰伦/江源东", "中国好声音第四季 荣耀之战", "000zuqG92V3O6w", 241),
                hit("七里香", "周杰伦", "七里香", "003DFRzD192KKD", 299),
                hit("稻香", "周杰伦", "魔杰座", "002Neh8l0uciQZ", 223),
                hit("一路向北", "周杰伦", "J III MP3 Player", "002MAeob3zLXwZ", 294),
                hit("兰亭序", "周杰伦", "魔杰座", "002Neh8l0uciQZ", 253)
        ));

        List<CoverCandidateVO> candidates = service.search(autoDto("青花瓷", "周杰伦"));

        assertEquals(3, candidates.size(), "应只保留 3 个青花瓷版本，实际：" + describe(candidates));
        assertTrue(candidates.stream().allMatch(c -> c.getTrackName().startsWith("青花瓷")),
                "候选里不能出现《稻香》《夜曲》这类无关曲目：" + describe(candidates));
        assertTrue(candidates.stream().allMatch(c -> c.getAlbumMid() != null),
                "没有 albumMid 的条目无法拼出封面，必须被跳过");
    }

    /** 按专辑去重：同一张专辑的多首歌共用一张封面，不能重复列出。 */
    @Test
    @DisplayName("按 albumMid 去重：同一张专辑只出一个候选")
    void deduplicatesByAlbum() {
        // 《魔杰座》里两首歌都叫「稻香」的不同版本，albumMid 相同
        catalog.respond("稻香 周杰伦", List.of(
                hit("稻香", "周杰伦", "魔杰座", "002Neh8l0uciQZ", 223),
                hit("稻香 (Live)", "周杰伦", "魔杰座", "002Neh8l0uciQZ", 225)
        ));

        List<CoverCandidateVO> candidates = service.search(autoDto("稻香", "周杰伦"));

        assertEquals(1, candidates.size(), "同一 albumMid 只能出现一次：" + describe(candidates));
        assertEquals("002Neh8l0uciQZ", candidates.get(0).getAlbumMid());
    }

    /** 别人的同名翻唱必须挡掉（歌手没有交集）。 */
    @Test
    @DisplayName("歌手无交集时丢弃：挡掉别人的同名翻唱")
    void rejectsDifferentArtist() {
        catalog.respond("孤勇者 陈奕迅", List.of(
                hit("孤勇者", "陈奕迅", "孤勇者", "004Z8Ihr0JIu5s", 256),
                hit("孤勇者", "某网络歌手", "翻唱合辑", "003abcdefghijk", 240)
        ));

        List<CoverCandidateVO> candidates = service.search(autoDto("孤勇者", "陈奕迅"));

        assertEquals(1, candidates.size(), "只保留陈奕迅本人的版本：" + describe(candidates));
        assertEquals("陈奕迅", candidates.get(0).getArtistName());
    }

    /** 合唱版（候选歌手多于本地）仍应保留 —— 否则会漏掉合法的合唱封面。 */
    @Test
    @DisplayName("合唱版保留：候选歌手是本地歌手的超集时仍算匹配")
    void keepsCollaborationVersion() {
        catalog.respond("青花瓷 周杰伦", List.of(
                hit("青花瓷 (Live)", "周杰伦/江源东", "中国好声音第四季", "000zuqG92V3O6w", 241)
        ));

        List<CoverCandidateVO> candidates = service.search(autoDto("青花瓷", "周杰伦"));

        assertEquals(1, candidates.size(), "合唱版应被保留：" + describe(candidates));
    }

    /** 候选数受 limit 约束，默认自动模式 4 个。 */
    @Test
    @DisplayName("自动模式默认最多 4 个候选")
    void autoModeLimit() {
        List<QqSongHit> hits = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            hits.add(hit("测试歌", "测试歌手", "专辑" + i, "0000000000000" + i, 200));
        }
        catalog.respond("测试歌 测试歌手", hits);

        List<CoverCandidateVO> candidates = service.search(autoDto("测试歌", "测试歌手"));

        assertEquals(4, candidates.size(), "自动模式默认上限 4：" + describe(candidates));
    }

    // ------------------------------------------------------------------ 自动模式：退化检索

    /**
     * 组合关键词命中不足时，应退化到纯歌名再搜一轮。
     *
     * <p>与歌词链路同样的理由：某些情况下「歌名 歌手」会返回专辑里的其它曲目，
     * 反而搜不到目标歌。判据必须是「过滤后的可用候选数」而不是「结果是否为空」。
     */
    @Test
    @DisplayName("组合关键词命中不足时退化到纯歌名，并合并两轮结果")
    void fallsBackToTrackOnly() {
        catalog.respond("冷门歌 冷门歌手", List.of(
                hit("别的歌", "冷门歌手", "某专辑", "000000000000a1", 200)
        ));
        catalog.respond("冷门歌", List.of(
                hit("冷门歌", "冷门歌手", "正确专辑", "000000000000b2", 210)
        ));

        List<CoverCandidateVO> candidates = service.search(autoDto("冷门歌", "冷门歌手"));

        assertEquals(1, candidates.size(), "退化检索应找到目标：" + describe(candidates));
        assertEquals("正确专辑", candidates.get(0).getAlbumName());
        assertTrue(catalog.queries.contains("冷门歌"), "必须发起纯歌名检索，实际：" + catalog.queries);
    }

    // ------------------------------------------------------------------ 手动模式

    /**
     * 手动模式不做歌名/歌手过滤。
     *
     * <p>这是本次需求的逃生通道：元数据解析错误时（歌名乱码、歌手填成文件名），
     * 自动模式会拿错误信息去搜，结果必然也错。手动模式下管理员自己输入关键词，
     * 服务端原样检索，不再用不可信的元数据做任何裁剪。
     */
    @Test
    @DisplayName("手动模式不做过滤：关键词原样检索，全部按专辑聚拢")
    void manualModeSkipsFiltering() {
        catalog.respond("周杰伦", List.of(
                hit("青花瓷", "周杰伦", "我很忙", "002eFUFm2XYZ7z", 239),
                hit("稻香", "周杰伦", "魔杰座", "002Neh8l0uciQZ", 223),
                hit("七里香", "周杰伦", "七里香", "003DFRzD192KKD", 299)
        ));

        CoverSearchDTO dto = new CoverSearchDTO();
        dto.setKeyword("周杰伦");

        List<CoverCandidateVO> candidates = service.search(dto);

        assertEquals(3, candidates.size(),
                "手动模式不应按歌名过滤，三首不同的歌都该出现：" + describe(candidates));
        assertTrue(catalog.queries.contains("周杰伦"), "关键词应原样使用，实际：" + catalog.queries);
    }

    /**
     * 手动模式支持只填专辑名。
     *
     * <p>实测搜「我很忙」能返回整张专辑的全部曲目，每首歌都指向同一个 albumMid，
     * 去重后正好得到一张封面 —— 这是「只记得专辑名」时最直接的用法。
     */
    @Test
    @DisplayName("手动模式支持只搜专辑名，按专辑去重后得到一张封面")
    void manualModeSupportsAlbumOnlyKeyword() {
        catalog.respond("我很忙", List.of(
                hit("青花瓷", "周杰伦", "我很忙", "002eFUFm2XYZ7z", 239),
                hit("我不配", "周杰伦", "我很忙", "002eFUFm2XYZ7z", 244),
                hit("最长的电影", "周杰伦", "我很忙", "002eFUFm2XYZ7z", 244),
                hit("彩虹", "周杰伦", "我很忙", "002eFUFm2XYZ7z", 264)
        ));

        CoverSearchDTO dto = new CoverSearchDTO();
        dto.setKeyword("我很忙");

        List<CoverCandidateVO> candidates = service.search(dto);

        assertEquals(1, candidates.size(), "同一专辑去重后应只剩 1 个：" + describe(candidates));
        assertEquals("我很忙", candidates.get(0).getAlbumName());
    }

    /** 手动模式默认上限 8（比自动模式宽），因为召回里混着同名不同版本。 */
    @Test
    @DisplayName("手动模式默认上限 8")
    void manualModeLimit() {
        List<QqSongHit> hits = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            hits.add(hit("歌" + i, "歌手", "专辑" + i, String.format("0000000000%04d", i), 200));
        }
        catalog.respond("歌手", hits);

        CoverSearchDTO dto = new CoverSearchDTO();
        dto.setKeyword("歌手");

        List<CoverCandidateVO> candidates = service.search(dto);

        assertEquals(8, candidates.size(), "手动模式默认上限 8：" + describe(candidates));
    }

    // ------------------------------------------------------------------ 边界

    /** 歌名与关键词都为空：直接返回空，不打外部接口。 */
    @Test
    @DisplayName("歌名与关键词都为空时不发起请求")
    void rejectsEmptyRequest() {
        List<CoverCandidateVO> candidates = service.search(new CoverSearchDTO());

        assertTrue(candidates.isEmpty());
        assertTrue(catalog.queries.isEmpty(), "参数不合法时不应请求外部接口：" + catalog.queries);
    }

    /** 全部候选都没有 albumMid：返回空列表而不是抛异常。 */
    @Test
    @DisplayName("全部候选缺 albumMid 时返回空列表，不抛异常")
    void toleratesMissingAlbumMid() {
        catalog.respond("青花瓷 周杰伦", List.of(
                hit("青花瓷", "周杰伦", null, null, 239),
                hit("青花瓷 (Live)", "周杰伦", null, null, 238)
        ));

        List<CoverCandidateVO> candidates = service.search(autoDto("青花瓷", "周杰伦"));

        assertTrue(candidates.isEmpty(), "无法拼出封面时应返回空：" + describe(candidates));
    }

    /** 每个候选都要带上三档封面地址，前端切换尺寸时无需再请求后端。 */
    @Test
    @DisplayName("候选带上 300/500/800 三档封面地址")
    void candidateCarriesAllSizes() {
        catalog.respond("青花瓷 周杰伦", List.of(
                hit("青花瓷", "周杰伦", "我很忙", "002eFUFm2XYZ7z", 239)
        ));

        List<CoverCandidateVO> candidates = service.search(autoDto("青花瓷", "周杰伦"));

        assertEquals(1, candidates.size());
        Map<String, String> urls = candidates.get(0).getCoverUrls();
        assertEquals(3, urls.size(), "应有三档尺寸：" + urls);
        assertTrue(urls.get("500").contains("T002R500x500M000002eFUFm2XYZ7z.jpg"), "实际：" + urls);
        assertTrue(urls.get("800").contains("T002R800x800"), "实际：" + urls);
    }

    /** 版本标签：录音室 / Live / 翻唱 / 伴奏 / 混音。 */
    @Test
    @DisplayName("版本标签正确识别录音室、Live、翻唱、伴奏、混音")
    void versionLabels() {
        assertEquals("录音室", service.versionLabel("青花瓷"));
        assertEquals("Live", service.versionLabel("青花瓷 (Live)"));
        assertEquals("Live", service.versionLabel("青花瓷 现场版"));
        assertEquals("翻唱", service.versionLabel("青花瓷 (中国好声音第四季)"));
        assertEquals("伴奏", service.versionLabel("青花瓷 (伴奏)"));
        assertEquals("伴奏", service.versionLabel("青花瓷 (钢琴版)"));
        assertEquals("混音", service.versionLabel("青花瓷 (DJ版)"));
    }

    // ------------------------------------------------------------------ 夹具

    private CoverSearchDTO autoDto(String songName, String artistName) {
        CoverSearchDTO dto = new CoverSearchDTO();
        dto.setSongName(songName);
        dto.setArtistName(artistName);
        return dto;
    }

    private static QqSongHit hit(String name, String artist, String albumName,
                                 String albumMid, Integer interval) {
        return new QqSongHit("mid-" + name + "-" + albumMid, name, artist, albumName, albumMid, interval);
    }

    private static String describe(List<CoverCandidateVO> candidates) {
        return candidates.stream()
                .map(c -> c.getTrackName() + "/" + c.getAlbumName() + "/" + c.getAlbumMid())
                .toList()
                .toString();
    }

    /** 假检索器：按关键词返回预设结果，并记录被问到的关键词顺序。 */
    private static final class FakeCatalog extends QqMusicCatalog {

        private final Map<String, List<QqSongHit>> responses = new java.util.LinkedHashMap<>();
        private final List<String> queries = new ArrayList<>();

        FakeCatalog respond(String keyword, List<QqSongHit> hits) {
            responses.put(keyword, hits);
            return this;
        }

        @Override
        public List<QqSongHit> search(String keyword, int limit) {
            queries.add(keyword);
            List<QqSongHit> hits = responses.getOrDefault(keyword, List.of());
            return hits.size() > limit ? hits.subList(0, limit) : hits;
        }

        @Override
        public boolean enabled() {
            return true;
        }
    }
}
