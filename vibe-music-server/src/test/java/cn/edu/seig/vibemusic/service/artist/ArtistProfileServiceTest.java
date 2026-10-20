package cn.edu.seig.vibemusic.service.artist;

import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.model.entity.Artist;
import cn.edu.seig.vibemusic.model.vo.ArtistCandidateVO;
import cn.edu.seig.vibemusic.model.vo.ArtistProfileVO;
import cn.edu.seig.vibemusic.service.cover.CoverDownloader;
import cn.edu.seig.vibemusic.service.cover.CoverNameNormalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 歌手资料的检索与自动填充。
 *
 * <p>重点验证三件"不做就会出错"的事：
 * <ul>
 *   <li><b>去括号回退</b>：库里 {@code IU (아이유)} 这类写法原样搜是空的，
 *       去掉括号才能命中（实测 4 位歌手靠它才搜到）；</li>
 *   <li><b>不自动预选非完全匹配</b>：{@code 周杰伦} 搜到 {@code 周杰伦战队} 时
 *       {@code exactMatch} 必须是 false，否则前端会替人选错人；</li>
 *   <li><b>已存在检测</b>：候选已在库里时要标出来，避免重复创建同名歌手。</li>
 * </ul>
 */
class ArtistProfileServiceTest {

    private static final String MID_JAY = "0025NhlN2yWrP4";
    private static final String MID_IU = "004Bjyj52RTYOj";

    private QqArtistCatalog catalog;
    private ArtistMapper artistMapper;
    private CoverDownloader downloader;
    private ArtistProfileService service;

    @BeforeEach
    void setUp() {
        catalog = mock(QqArtistCatalog.class);
        artistMapper = mock(ArtistMapper.class);
        downloader = mock(CoverDownloader.class);

        ArtistCandidateRanker ranker = new ArtistCandidateRanker();
        ReflectionTestUtils.setField(ranker, "normalizer", new CoverNameNormalizer());

        service = new ArtistProfileService();
        ReflectionTestUtils.setField(service, "catalog", catalog);
        ReflectionTestUtils.setField(service, "ranker", ranker);
        ReflectionTestUtils.setField(service, "artistMapper", artistMapper);
        ReflectionTestUtils.setField(service, "downloader", downloader);

        when(artistMapper.selectList(any())).thenReturn(List.of());
    }

    // ------------------------------------------------------------------ 搜索

    @Test
    @DisplayName("空白关键词直接返回空，不打接口")
    void blankKeywordReturnsEmpty() {
        assertTrue(service.search(null).isEmpty());
        assertTrue(service.search("").isEmpty());
        assertTrue(service.search("   ").isEmpty());
        verify(catalog, never()).search(anyString(), anyInt());
    }

    @Test
    @DisplayName("原样命中时不做第二段搜索")
    void exactSearchDoesNotRetry() {
        when(catalog.search(eq("周杰伦"), anyInt()))
                .thenReturn(List.of(new QqArtistHit(MID_JAY, "周杰伦", "11")));

        List<ArtistCandidateVO> candidates = service.search("周杰伦");

        assertEquals(1, candidates.size());
        assertEquals("周杰伦", candidates.get(0).getArtistName());
        assertTrue(candidates.get(0).getExactMatch(), "完全一致应可自动预选");
        verify(catalog, times(1)).search(anyString(), anyInt());
    }

    @Test
    @DisplayName("原名搜不到且带尾部括号时，去掉括号再搜一次")
    void fallsBackToStrippedName() {
        // 模拟 IU (아이유)：原名无结果，去括号后命中
        when(catalog.search(eq("IU (아이유)"), anyInt())).thenReturn(List.of());
        when(catalog.search(eq("IU"), anyInt()))
                .thenReturn(List.of(new QqArtistHit(MID_IU, "IU", "8")));

        List<ArtistCandidateVO> candidates = service.search("IU (아이유)");

        assertEquals(1, candidates.size());
        assertEquals("IU", candidates.get(0).getArtistName());
        assertTrue(candidates.get(0).getExactMatch(),
                "去括号后归一化一致，应视为完全匹配");
        verify(catalog, times(1)).search(eq("IU (아이유)"), anyInt());
        verify(catalog, times(1)).search(eq("IU"), anyInt());
    }

    @Test
    @DisplayName("原名搜不到但不带括号时，不做无谓的第二次搜索")
    void noRetryWithoutBracket() {
        when(catalog.search(anyString(), anyInt())).thenReturn(List.of());

        assertTrue(service.search("完全不存在的歌手").isEmpty());
        verify(catalog, times(1)).search(anyString(), anyInt());
    }

    @Test
    @DisplayName("包含关系不自动预选 —— 周杰伦 vs 周杰伦战队")
    void derivedAccountIsNotPreselected() {
        when(catalog.search(eq("周杰伦"), anyInt())).thenReturn(List.of(
                new QqArtistHit("00203xA13apUJN", "周杰伦战队", "5"),
                new QqArtistHit(MID_JAY, "周杰伦", "11")));

        List<ArtistCandidateVO> candidates = service.search("周杰伦");

        // 排序：完全一致的排前面
        assertEquals("周杰伦", candidates.get(0).getArtistName());
        assertTrue(candidates.get(0).getExactMatch());

        ArtistCandidateVO derived = candidates.stream()
                .filter(c -> "周杰伦战队".equals(c.getArtistName()))
                .findFirst().orElseThrow();
        assertFalse(derived.getExactMatch(), "衍生账号不能自动预选");
        assertTrue(derived.getScore() < candidates.get(0).getScore());
    }

    @Test
    @DisplayName("候选已在库里时标出 existingInLibrary 与 artistId")
    void marksExistingArtists() {
        when(catalog.search(eq("周杰伦"), anyInt()))
                .thenReturn(List.of(new QqArtistHit(MID_JAY, "周杰伦", "11")));
        when(artistMapper.selectList(any())).thenReturn(List.of(artist(142L, "周杰伦")));

        ArtistCandidateVO candidate = service.search("周杰伦").get(0);

        assertTrue(candidate.getExistingInLibrary());
        assertEquals(142L, candidate.getExistingArtistId());
    }

    @Test
    @DisplayName("库里的括号写法也能认出是同一人 —— IU (아이유) 对上 IU")
    void matchesAcrossBracketDifference() {
        when(catalog.search(eq("IU"), anyInt()))
                .thenReturn(List.of(new QqArtistHit(MID_IU, "IU", "8")));
        when(artistMapper.selectList(any())).thenReturn(List.of(artist(17L, "IU (아이유)")));

        ArtistCandidateVO candidate = service.search("IU").get(0);

        assertTrue(candidate.getExistingInLibrary(), "去括号后应认出是同一位歌手");
        assertEquals(17L, candidate.getExistingArtistId());
    }

    @Test
    @DisplayName("每个候选都带上三档头像地址")
    void candidatesCarryAllAvatarSizes() {
        when(catalog.search(eq("周杰伦"), anyInt()))
                .thenReturn(List.of(new QqArtistHit(MID_JAY, "周杰伦", "11")));

        Map<String, String> urls = service.search("周杰伦").get(0).getAvatarUrls();

        assertEquals(List.of("300", "500", "800"), List.copyOf(urls.keySet()));
        assertTrue(urls.get("800").contains("T001R800x800"));
    }

    // ------------------------------------------------------------------ 详情

    @Test
    @DisplayName("详情把 QQ 字段映射到 VO，并列出实际填充了哪几项")
    void mapsProfileFields() {
        when(catalog.detail(MID_JAY)).thenReturn(profile());

        ArtistProfileVO vo = service.detail(MID_JAY);

        assertNotNull(vo);
        assertEquals("周杰伦", vo.getArtistName());
        assertEquals(0, vo.getGender());
        assertEquals(LocalDate.of(1979, 1, 18), vo.getBirth());
        assertEquals("中国", vo.getArea());
        assertEquals("华语流行男歌手", vo.getIntroduction());
        assertEquals("Jay Chou", vo.getForeignName());
        assertEquals(List.of("gender", "birth", "area", "introduction", "avatar"),
                vo.getFilledFields());
        assertTrue(vo.getExtraFields().containsKey("出生地"));
    }

    @Test
    @DisplayName("详情里缺失的字段不进入 filledFields，且值为 null")
    void missingFieldsAreNullAndNotListed() {
        QqArtistProfile sparse = new QqArtistProfile(MID_JAY, "某独立音乐人", null, null,
                null, null, null, null, 0, Map.of());
        when(catalog.detail(MID_JAY)).thenReturn(sparse);

        ArtistProfileVO vo = service.detail(MID_JAY);

        assertNull(vo.getGender());
        assertNull(vo.getBirth());
        assertNull(vo.getArea());
        assertNull(vo.getIntroduction());
        assertTrue(vo.getFilledFields().isEmpty(), "什么都没查到就不该声称填充了内容");
    }

    @Test
    @DisplayName("对方无此歌手时返回 null，让控制器给出可读提示")
    void unknownSingerReturnsNull() {
        when(catalog.detail(MID_JAY)).thenReturn(null);

        assertNull(service.detail(MID_JAY));
    }

    @Test
    @DisplayName("详情也会标出该歌手已在库中")
    void detailMarksExisting() {
        when(catalog.detail(MID_JAY)).thenReturn(profile());
        when(artistMapper.selectList(any())).thenReturn(List.of(artist(142L, "周杰伦")));

        ArtistProfileVO vo = service.detail(MID_JAY);

        assertTrue(vo.getExistingInLibrary());
        assertEquals(142L, vo.getExistingArtistId());
    }

    // ------------------------------------------------------------------ 头像下载

    @Test
    @DisplayName("非法 mid 不发起下载")
    void downloadRejectsInvalidMid() {
        CoverDownloader.Result result = service.downloadAvatar("abc", 500, null);

        assertFalse(result.success());
        assertEquals("歌手标识不合法", result.error());
        verify(downloader, never()).download(anyString());
    }

    @Test
    @DisplayName("下载失败时把提示里的「专辑」换成「歌手」")
    void downloadRewritesMessage() {
        // CoverDownloader 的原始提示是给封面用的，直接透出会让人困惑
        when(downloader.download(anyString()))
                .thenReturn(new CoverDownloader.Result(null, null, "该专辑没有可用的封面图"));

        CoverDownloader.Result result = service.downloadAvatar(MID_JAY, 500, null);

        assertFalse(result.success());
        assertEquals("该歌手没有可用的封面图", result.error());
    }

    @Test
    @DisplayName("下载成功时原样返回字节与 Content-Type")
    void downloadPassesThroughOnSuccess() {
        byte[] bytes = new byte[]{1, 2, 3};
        when(downloader.download(anyString()))
                .thenReturn(new CoverDownloader.Result(bytes, "image/jpeg", null));

        CoverDownloader.Result result = service.downloadAvatar(MID_JAY, 800, "11");

        assertTrue(result.success());
        assertEquals(3, result.bytes().length);
        assertEquals("image/jpeg", result.contentType());
    }

    // ------------------------------------------------------------------ helpers

    private Artist artist(Long id, String name) {
        Artist artist = new Artist();
        artist.setArtistId(id);
        artist.setArtistName(name);
        artist.setStatus("ACTIVE");
        return artist;
    }

    private QqArtistProfile profile() {
        Map<String, String> wiki = new LinkedHashMap<>();
        wiki.put("外文名", "Jay Chou");
        wiki.put("国籍", "中国");
        wiki.put("出生地", "台湾省新北市");
        return new QqArtistProfile(MID_JAY, "周杰伦", 0, LocalDate.of(1979, 1, 18),
                "中国", "华语流行男歌手", "Jay Chou", "11", 11, wiki);
    }
}
