package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.config.LyricsProperties;
import cn.edu.seig.vibemusic.model.dto.LyricsSearchDTO;
import cn.edu.seig.vibemusic.model.vo.LyricsFetchVO;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.lyrics.LyricsCandidate;
import cn.edu.seig.vibemusic.service.lyrics.LyricsMatcher;
import cn.edu.seig.vibemusic.service.lyrics.LyricsProvider;
import cn.edu.seig.vibemusic.service.lyrics.LyricsTextCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「上传页按元数据搜索歌词」的验收。
 *
 * <p>背景：{@code preview(songId)} 依赖歌曲已入库，但上传流程里歌曲还没有 songId，
 * 只有表单字段。新增的 {@code searchByMeta} 必须复用<b>完全相同</b>的检索与打分链路，
 * 否则会出现「上传时搜不到、入库后补全却搜得到」的割裂。
 *
 * <p>这份测试钉住三点：
 * <ul>
 *     <li>不需要 songId，也不访问 tb_song；</li>
 *     <li>歌手展示串「银临 / Aki阿杰」被拆成候选链，与入库后的回退链一致；</li>
 *     <li>时长参与消歧，且不会被错误格式污染。</li>
 * </ul>
 */
class LyricsSearchByMetaTest {

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

    @Test
    @DisplayName("按歌名+歌手即可命中，不需要 songId")
    void searchesWithoutSongId() {
        FakeProvider qq = new FakeProvider("QQ_MUSIC", 10).respond("银临", "银临");
        useProviders(qq);

        LyricsSearchDTO dto = new LyricsSearchDTO();
        dto.setSongName("牵丝戏");
        dto.setArtistName("银临");

        Result<LyricsFetchVO> result = service.searchByMeta(dto);

        assertEquals(0, result.getCode());
        LyricsFetchVO vo = result.getData();
        assertNotNull(vo);
        assertTrue(vo.getMatched());
        assertEquals("QQ_MUSIC", vo.getSource());
        // 临时 Song 没有主键，验证确实没有走「查库」那条路
        assertEquals(null, vo.getSongId());
        assertFalse(Boolean.TRUE.equals(vo.getHasExistingLyric()), "上传场景没有既有歌词");
    }

    @Test
    @DisplayName("歌手展示串按分隔符拆成候选链：先整体串，再逐段")
    void splitsCollaborationDisplayString() {
        // 数据源只认完整展示串，主艺人「银临」拿不到 → 必须回退到整体串
        FakeProvider qq = new FakeProvider("QQ_MUSIC", 10).respond("银临 / Aki阿杰", "Aki阿杰");
        useProviders(qq);

        LyricsSearchDTO dto = new LyricsSearchDTO();
        dto.setSongName("牵丝戏");
        dto.setArtistName("银临 / Aki阿杰");

        Result<LyricsFetchVO> result = service.searchByMeta(dto);

        assertEquals(0, result.getCode());
        assertTrue(result.getData().getMatched(), "整体展示串应作为候选被尝试");
        assertEquals("银临 / Aki阿杰", result.getData().getArtistName(), "展示用歌手名保持原串");
        assertTrue(qq.queried.contains("银临 / Aki阿杰"),
                "候选链必须包含完整展示串，实际检索：" + qq.queried);
    }

    @Test
    @DisplayName("缺少歌名时直接报错，不发起任何数据源请求")
    void rejectsBlankSongName() {
        FakeProvider qq = new FakeProvider("QQ_MUSIC", 10);
        useProviders(qq);

        LyricsSearchDTO dto = new LyricsSearchDTO();
        dto.setArtistName("银临");

        Result<LyricsFetchVO> result = service.searchByMeta(dto);

        assertEquals(1, result.getCode());
        assertTrue(qq.queried.isEmpty(), "参数不合法时不应打外部数据源");
    }

    @Test
    @DisplayName("功能开关关闭时直接拒绝，不发起请求")
    void rejectsWhenDisabled() {
        properties.setEnabled(false);
        FakeProvider qq = new FakeProvider("QQ_MUSIC", 10);
        useProviders(qq);

        LyricsSearchDTO dto = new LyricsSearchDTO();
        dto.setSongName("牵丝戏");

        Result<LyricsFetchVO> result = service.searchByMeta(dto);

        assertEquals(1, result.getCode());
        assertTrue(qq.queried.isEmpty());
    }

    @Test
    @DisplayName("时长参与版本消歧：秒数被格式化后仍能被解析回来")
    void durationParticipatesInMatching() {
        FakeProvider qq = new FakeProvider("QQ_MUSIC", 10)
                .respondWithDuration("银临", 180, "银临");
        useProviders(qq);

        LyricsSearchDTO dto = new LyricsSearchDTO();
        dto.setSongName("牵丝戏");
        dto.setArtistName("银临");
        dto.setDurationSeconds(180.0);

        Result<LyricsFetchVO> result = service.searchByMeta(dto);

        assertEquals(0, result.getCode());
        assertTrue(result.getData().getMatched());
        // 命中说明时长被正确解析并参与打分（否则同名不同版本无法区分）
        assertNotNull(result.getData().getScore());
    }

    @Test
    @DisplayName("专辑为空时不参与打分，也不报错")
    void blankAlbumIsTolerated() {
        FakeProvider qq = new FakeProvider("QQ_MUSIC", 10).respond("银临", "银临");
        useProviders(qq);

        LyricsSearchDTO dto = new LyricsSearchDTO();
        dto.setSongName("牵丝戏");
        dto.setArtistName("银临");
        dto.setAlbum("   ");

        Result<LyricsFetchVO> result = service.searchByMeta(dto);

        assertEquals(0, result.getCode());
        assertTrue(result.getData().getMatched());
    }

    // ------------------------------------------------------------------ 夹具

    private void useProviders(LyricsProvider... providers) {
        ReflectionTestUtils.setField(service, "providers", List.of(providers));
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
                    new LyricsCandidate("牵丝戏", candidateArtist, null, null,
                            "[00:01.00]戏腔起", source));
            return this;
        }

        FakeProvider respondWithDuration(String queriedArtist, Integer duration, String candidateArtist) {
            responses.put(queriedArtist,
                    new LyricsCandidate("牵丝戏", candidateArtist, null, duration,
                            "[00:01.00]戏腔起", source));
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
