package cn.edu.seig.vibemusic.agent;

import cn.edu.seig.vibemusic.model.agent.SongRef;
import cn.edu.seig.vibemusic.model.vo.ArtistNameVO;
import cn.edu.seig.vibemusic.service.ArtistNameParser;
import cn.edu.seig.vibemusic.service.SongArtistService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 多歌手改造的契约测试。
 *
 * <p>要守住的是一条硬规则（docs/phase6-song-multiple-artists.md §4.4）：
 * <b>展示串 {@code artistName} 与结构化列表 {@code artists} 必须由同一份权威数据派生</b>，
 * 不允许出现「展示串里有 Aki阿杰、artists 里没有」这种分裂。
 */
class SongArtistContractTest extends AbstractAgentTest {

    private static final String SEP = SongArtistService.DISPLAY_SEPARATOR;

    /** 任何返回的歌曲，展示串都必须等于 artists 按顺序拼接的结果。 */
    @Test
    void everySongExposesArtistsConsistentWithArtistName() {
        var result = songQuery.page(null, "测试歌手", null, 1, 20);

        assertThat(result.songs()).isNotEmpty();
        for (SongRef song : result.songs()) {
            assertThat(song.artists()).isNotNull();
            assertThat(song.artistName())
                    .isEqualTo(song.artists().stream()
                            .map(ArtistNameVO::getArtistName)
                            .collect(Collectors.joining(SEP)));
        }
    }

    /** 合作歌曲：两位歌手按 display_order 返回，且带真实 artistId（前端据此跳转歌手页）。 */
    @Test
    void cooperatedSongKeepsDisplayOrderAndBothRealArtistIds() {
        jdbc.update("INSERT INTO tb_song(id,name,artist_id,artist_names,style) "
                + "VALUES (20,'合唱曲',1,'测试歌手A / 测试歌手B','流行')");
        jdbc.update("INSERT INTO tb_song_artist(song_id, artist_id, display_order) VALUES (20,1,1),(20,2,2)");

        // 用非主艺人（测试歌手B）检索也必须命中这首合作歌曲。
        var songs = songQuery.search(null, "测试歌手B", null, null, 20);
        SongRef cooperated = songs.stream()
                .filter(song -> song.songId() == 20L)
                .findFirst()
                .orElseThrow(() -> new AssertionError("合作歌曲未按非主艺人检索命中"));

        assertThat(cooperated.artistName()).isEqualTo("测试歌手A / 测试歌手B");
        assertThat(cooperated.artists()).extracting(ArtistNameVO::getArtistId)
                .containsExactly(1L, 2L);
        assertThat(cooperated.artists()).extracting(ArtistNameVO::getArtistName)
                .containsExactly("测试歌手A", "测试歌手B");
    }

    /** 拼接逻辑全项目唯一：空白项被丢弃、去首尾空格、分隔符固定为 " / "。 */
    @Test
    void renderArtistNamesIsTheSingleSourceOfTheDisplayString() {
        assertThat(SongArtistService.renderArtistNames(List.of("银临", "Aki阿杰")))
                .isEqualTo("银临 / Aki阿杰");
        assertThat(SongArtistService.renderArtistNames(List.of(" 银临 ", "Aki阿杰")))
                .isEqualTo("银临 / Aki阿杰");
        assertThat(SongArtistService.renderArtistNames(List.of("银临", "", "  ")))
                .isEqualTo("银临");
        assertThat(SongArtistService.renderArtistNames(List.of())).isNull();
        assertThat(SongArtistService.renderArtistNames(null)).isNull();
    }

    /**
     * 绕过服务层直接插库的歌曲没有关系行时，读路径不编造歌手：
     * {@code artists} 是空列表（不是 null），{@code artistName} 为 null。
     * 这同时说明了为什么写入路径必须走 {@code SongArtistService} —— 见 P4。
     */
    @Test
    void songWithoutRelationGetsEmptyListNotNull() {
        jdbc.update("INSERT INTO tb_song(id,name,artist_id,style) VALUES (21,'孤儿歌',1,'流行')");

        var songs = songQuery.search("孤儿歌", null, null, null, 20);

        assertThat(songs).hasSize(1);
        assertThat(songs.get(0).artists()).isEmpty();
        assertThat(songs.get(0).artistName()).isNull();
    }

    // ================================================================== 写路径

    /** 整组替换：顺序即 display_order，主艺人与展示串同步落到 tb_song。 */
    @Test
    void replaceSongArtistsWritesOrderedRelationsAndDerivedColumns() {
        songArtists.replaceSongArtists(10L, List.of(2L, 1L));

        assertThat(jdbc.queryForList(
                "SELECT artist_id, display_order FROM tb_song_artist WHERE song_id = 10 ORDER BY display_order"))
                .extracting(row -> row.get("artist_id"), row -> row.get("display_order"))
                .containsExactly(tuple(2L, 1), tuple(1L, 2));

        assertThat(jdbc.queryForObject("SELECT artist_id FROM tb_song WHERE id = 10", Long.class))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT artist_names FROM tb_song WHERE id = 10", String.class))
                .isEqualTo("测试歌手B / 测试歌手A");
    }

    /** 空列表与非正数会被规范化掉；规范化后为空则直接拒绝，不允许出现「无歌手歌曲」。 */
    @Test
    void replaceSongArtistsRejectsEmptyArtistList() {
        assertThatThrownBy(() -> songArtists.replaceSongArtists(10L, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> songArtists.replaceSongArtists(10L, List.of(0L, -1L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 事务性：一位歌手不存在时整组回滚，原有关系与展示串都不能被改动，
     * 也不能留下「只写了一半」的关系行。
     */
    @Test
    void replaceSongArtistsRollsBackWhenAnyArtistIsUnknown() {
        String beforeName = jdbc.queryForObject("SELECT artist_names FROM tb_song WHERE id = 10", String.class);

        assertThatThrownBy(() -> songArtists.replaceSongArtists(10L, List.of(1L, 9999L)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_song_artist WHERE song_id = 10", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT artist_names FROM tb_song WHERE id = 10", String.class))
                .isEqualTo(beforeName);
    }

    /** 歌手改名后重建展示串：受影响的歌曲必须立刻反映新名字。 */
    @Test
    void rebuildArtistNamesAfterRename() {
        jdbc.update("UPDATE tb_artist SET name = '测试歌手B改' WHERE id = 2");

        int updated = songArtists.rebuildArtistNamesForArtist(2L);

        assertThat(updated).isEqualTo(2);
        assertThat(jdbc.queryForList(
                "SELECT artist_names FROM tb_song WHERE id IN (13,14)", String.class))
                .containsOnly("测试歌手B改");
    }

    // ================================================================== 上传解析

    /** 完整匹配优先：本身就是一位现有歌手，绝不拆分。 */
    @Test
    void parserPrefersExactMatchOverSplitting() {
        var result = artistNameParser.parse("测试歌手A");

        assertThat(result.getStrategy()).isEqualTo(ArtistNameParser.STRATEGY_EXACT_MATCH);
        assertThat(result.getCandidates()).singleElement()
                .satisfies(c -> {
                    assertThat(c.getArtistId()).isEqualTo(1L);
                    assertThat(c.isMatched()).isTrue();
                });
    }

    /** 每一段都唯一命中现有歌手 → 可自动拆分。 */
    @Test
    void parserConfirmsSplitWhenEveryPartMatches() {
        var result = artistNameParser.parse("测试歌手A/测试歌手B");

        assertThat(result.getStrategy()).isEqualTo(ArtistNameParser.STRATEGY_SPLIT_CONFIRMED);
        assertThat(result.getCandidates()).extracting("artistId").containsExactly(1L, 2L);
        assertThat(result.getCandidates()).allSatisfy(c -> assertThat(c.isMatched()).isTrue());
    }

    /** 有段落未匹配 → 进入人工确认，且未匹配的名称既不被创建也不被丢弃。 */
    @Test
    void parserFallsBackToManualConfirmationForUnknownPart() {
        var result = artistNameParser.parse("测试歌手A/Aki阿杰");

        assertThat(result.getStrategy()).isEqualTo(ArtistNameParser.STRATEGY_SPLIT_CANDIDATE);
        assertThat(result.getCandidates()).hasSize(2);
        assertThat(result.getCandidates().get(0).getArtistId()).isEqualTo(1L);
        assertThat(result.getCandidates().get(1).getArtistId()).isNull();
        assertThat(result.getCandidates().get(1).getArtistName()).isEqualTo("Aki阿杰");
        assertThat(result.getCandidates().get(1).isMatched()).isFalse();
    }

    /**
     * 逗号不是分隔符：库里存在「接个吻，开一枪」这种含中文逗号的合法艺名，
     * 若把逗号当分隔符会直接把它拆坏。
     */
    @Test
    void parserNeverTreatsCommaAsSeparator() {
        var result = artistNameParser.parse("接个吻，开一枪");

        assertThat(result.getStrategy()).isEqualTo(ArtistNameParser.STRATEGY_UNRESOLVED);
        assertThat(artistNameParser.looksSplittable("接个吻，开一枪")).isFalse();
        assertThat(artistNameParser.looksSplittable("测试歌手A/测试歌手B")).isTrue();
    }

    /** feat. 与全角顿号都在白名单内。 */
    @Test
    void parserRecognisesFeatAndIdeographicComma() {
        assertThat(artistNameParser.parse("测试歌手A feat. 测试歌手B").getStrategy())
                .isEqualTo(ArtistNameParser.STRATEGY_SPLIT_CONFIRMED);
        assertThat(artistNameParser.parse("测试歌手A、测试歌手B").getStrategy())
                .isEqualTo(ArtistNameParser.STRATEGY_SPLIT_CONFIRMED);
    }

    // ================================================================== 软删除（P5）

    /**
     * 停用歌手必须从「歌手侧」的全部入口消失：Agent 搜歌手、歌手详情、画像常听歌手。
     *
     * <p>这是 docs §7.3 那份「16 处要加、6 类不要加」清单里最有代表性的三条。
     * 歌曲侧的召回（{@code artist_names LIKE}）刻意不过滤 —— 那只是召回手段，
     * 而 {@code tb_song_artist} 才是归属的权威来源。
     */
    @Test
    void disabledArtistDisappearsFromArtistSideQueries() {
        jdbc.update("UPDATE tb_artist SET status = 'DISABLED' WHERE id = 2");

        // 1) Agent 搜歌手：命中 0 条（按 ToolResult 协议是「成功但无结果」= EMPTY，不是失败）
        String searchResult = musicTools.searchArtists("测试歌手B");
        assertThat(searchResult).contains("\"code\":\"EMPTY\"").contains("\"total\":0");

        // 2) Agent 歌手详情：判定为不存在
        assertThat(musicTools.getArtistDetail("测试歌手B")).contains("NOT_FOUND");

        // 3) 画像常听歌手：只剩启用的那位
        jdbc.update("INSERT INTO tb_user_favorite(id,user_id,type,song_id,create_time) VALUES "
                + "(1,1,0,10,CURRENT_TIMESTAMP),(2,1,0,13,CURRENT_TIMESTAMP)");
        var artists = profileService.read(1L).getTopArtists();
        assertThat(artists).contains("测试歌手A").doesNotContain("测试歌手B");
    }

    /** 停用歌手不能再被新的歌曲关系引用（否则会把已合并的组合歌手重新挂回歌曲上）。 */
    @Test
    void disabledArtistCannotBeAttachedToSongs() {
        jdbc.update("UPDATE tb_artist SET status = 'DISABLED' WHERE id = 2");

        assertThatThrownBy(() -> songArtists.replaceSongArtists(10L, List.of(2L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已停用");
    }

    /** 上传解析也必须忽略停用歌手：否则会把已合并的组合歌手当成「已匹配」直接自动关联。 */
    @Test
    void parserIgnoresDisabledArtists() {
        jdbc.update("UPDATE tb_artist SET status = 'DISABLED' WHERE id = 2");

        var result = artistNameParser.parse("测试歌手A/测试歌手B");

        assertThat(result.getStrategy()).isEqualTo(ArtistNameParser.STRATEGY_SPLIT_CANDIDATE);
        assertThat(result.getCandidates().get(1).isMatched()).isFalse();
    }

    // ================================================================== 专辑歌曲列表

    /**
     * 专辑内的歌曲必须显示<b>歌曲自己的</b>歌手列表，而不是专辑主艺人。
     *
     * <p>这是一条真实回归：早期 {@code AlbumMapper.getSongsByAlbumId} JOIN 了 {@code tb_artist}
     * 取专辑主艺人名，导致整张专辑的歌曲都被显示成同一个名字 —— 表现为
     * 「搜索某位歌手时，歌曲只显示该歌手名字」。谁再把 JOIN 加回来，这条就会红。
     */
    @Test
    void albumSongListShowsEachSongsOwnArtistsNotTheAlbumsMainArtist() {
        // 把专辑 1 里的歌曲 11 改成合作演唱（专辑主艺人仍是 1）
        jdbc.update("UPDATE tb_song SET artist_names = '测试歌手A / 测试歌手B' WHERE id = 11");
        jdbc.update("INSERT INTO tb_song_artist(song_id, artist_id, display_order) VALUES (11,2,2)");

        var songs = albumMapper.getSongsByAlbumId(1L);

        assertThat(songs).extracting("songId").containsExactlyInAnyOrder(10L, 11L, 12L);
        assertThat(songs).filteredOn(song -> song.getSongId().equals(11L))
                .singleElement()
                .satisfies(song -> assertThat(song.getArtistName()).isEqualTo("测试歌手A / 测试歌手B"));
        // 同专辑的独唱歌曲不受影响
        assertThat(songs).filteredOn(song -> song.getSongId().equals(10L))
                .singleElement()
                .satisfies(song -> assertThat(song.getArtistName()).isEqualTo("测试歌手A"));
    }

    // ================================================================== 管理端歌手名下歌曲

    /**
     * 管理端「查看歌手名下的歌曲」必须按<b>关联表</b>筛，不能用 {@code tb_song.artist_id}。
     *
     * <p>这是一条真实回归：合作歌曲的 {@code artist_id} 只记主艺人，所以早期实现
     * {@code s.artist_id = #{artistId}} 会让「非主艺人的那位歌手」名下永远查不到这首歌 ——
     * 表现为「Aki阿杰名下看不到《牵丝戏》」。谁把 EXISTS 换回等值判断，这条就会红。
     */
    @Test
    void adminSongListFindsCooperationSongByNonPrimaryArtistId() {
        // 歌曲 10 变成合作演唱，主艺人仍是 1，客串歌手是 2
        jdbc.update("UPDATE tb_song SET artist_names = '测试歌手A / 测试歌手B' WHERE id = 10");
        jdbc.update("INSERT INTO tb_song_artist(song_id, artist_id, display_order) VALUES (10,2,2)");

        // 按「非主艺人」测试歌手B（id=2）筛选：歌曲 10 必须出现
        var page = songMapper.getSongsWithArtistName(new Page<>(1, 10), 2L, null, null, null, false, "s.release_time DESC");

        assertThat(page.getTotal()).isEqualTo(3); // 10（合作）+ 13、14（独唱）
        assertThat(page.getRecords()).filteredOn(song -> song.getSongId().equals(10L))
                .singleElement()
                .satisfies(song -> assertThat(song.getArtistName()).isEqualTo("测试歌手A / 测试歌手B"));

        // 按主艺人（id=1）筛选：同样要包含这首合作歌曲
        var byPrimary = songMapper.getSongsWithArtistName(new Page<>(1, 10), 1L, null, null, null, false, "s.release_time DESC");
        assertThat(byPrimary.getRecords()).extracting("songId")
                .containsExactlyInAnyOrder(10L, 11L, 12L);
    }
}
