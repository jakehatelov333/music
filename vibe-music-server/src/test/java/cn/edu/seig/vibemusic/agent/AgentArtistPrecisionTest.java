package cn.edu.seig.vibemusic.agent;

import cn.edu.seig.vibemusic.model.agent.SongRef;
import cn.edu.seig.vibemusic.service.agent.AgentMetrics;
import cn.edu.seig.vibemusic.service.agent.UserMemoryPreferenceService.ExclusionConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P6 精确检索契约：歌手筛选一律走「解析 artistId → EXISTS 关联表」，不得退回展示串匹配。
 *
 * <p>夹具来自 {@link AbstractAgentTest}：歌手 1「测试歌手A」（歌 10/11/12）、
 * 歌手 2「测试歌手B」（歌 13/14）。
 */
class AgentArtistPrecisionTest extends AbstractAgentTest {

    /** 插入一首由两位歌手合作演唱的歌，关系表两行、展示串为「A / B」。 */
    private void insertCooperationSong() {
        jdbc.update("INSERT INTO tb_song(id,name,artist_id,artist_names,style) "
                + "VALUES (20,'合作曲',1,'测试歌手A / 测试歌手B','流行')");
        jdbc.update("INSERT INTO tb_song_artist(song_id, artist_id, display_order) VALUES (20,1,1),(20,2,2)");
    }

    @Test
    void cooperationSongIsFoundByEitherArtistThroughRelationTable() {
        insertCooperationSong();

        assertEquals(List.of(13L, 14L, 20L),
                songQuery.searchByArtistName("测试歌手B", 20).stream().map(SongRef::songId).toList());
        assertEquals(List.of(10L, 11L, 12L, 20L),
                songQuery.searchByArtistName("测试歌手A", 20).stream().map(SongRef::songId).toList());

        // 卡片上的歌手名仍是完整展示串（展示读 artist_names，与归属判定相互独立）
        assertEquals("测试歌手A / 测试歌手B",
                songQuery.searchByArtistName("测试歌手B", 20).stream()
                        .filter(s -> s.songId() == 20L).findFirst().orElseThrow().artistName());
    }

    @Test
    void fullDisplayStringInputStillResolvesToItsArtists() {
        insertCooperationSong();
        // 调用方原样传回展示串时，必须拆成两位歌手取并集，而不是解析失败导致召回凭空丢失
        List<Long> ids = songQuery.searchByArtistName("测试歌手A / 测试歌手B", 20).stream()
                .map(SongRef::songId).toList();
        assertTrue(ids.contains(20L));
        assertTrue(ids.containsAll(List.of(10L, 11L, 12L, 13L, 14L)));
    }

    @Test
    void disabledArtistIsNotSearchableAsAnArtist() {
        jdbc.update("INSERT INTO tb_artist(id,name,status) VALUES (9,'停用歌手','DISABLED')");
        jdbc.update("INSERT INTO tb_song(id,name,artist_id,artist_names,style) "
                + "VALUES (30,'停用歌手的旧歌',9,'停用歌手','流行')");
        jdbc.update("INSERT INTO tb_song_artist(song_id, artist_id, display_order) VALUES (30,9,1)");

        // 停用（软删除）歌手不得通过 Agent 的歌手检索被翻出来
        assertTrue(songQuery.searchByArtistName("停用歌手", 20).isEmpty());
    }

    @Test
    void unknownArtistYieldsEmptyRatherThanEverySong() {
        assertTrue(songQuery.searchByArtistName("不存在的歌手", 20).isEmpty());
        // 「给了歌手名但解析不到」必须是空结果，绝不能退化成「不加条件」
        assertEquals(0, songQuery.page(null, "不存在的歌手", null, 1, 5).total());
        assertTrue(songQuery.searchExcluding(null, "不存在的歌手", null, List.of(), ExclusionConstraints.none(), 10)
                .isEmpty());
    }

    @Test
    void paginationByArtistUsesOwnershipNotDisplayString() {
        insertCooperationSong();

        var page = songQuery.page(null, "测试歌手B", null, 1, 5);
        assertEquals(3, page.total());
        assertEquals(List.of(13L, 14L, 20L), page.songs().stream().map(SongRef::songId).toList());
        // 翻到第 2 页仍能拿到合作歌曲，且 total 不被关联行放大
        assertEquals(3, songQuery.page(null, "测试歌手B", null, 1, 2).total());
        assertEquals(List.of(20L), songQuery.page(null, "测试歌手B", null, 2, 2).songs().stream()
                .map(SongRef::songId).toList());
    }

    @Test
    void playlistCandidatesRespectArtistOwnershipAndExclusion() {
        insertCooperationSong();

        assertEquals(3, songQuery.searchExcluding(null, "测试歌手B", null, List.of(),
                ExclusionConstraints.none(), 10).size());
        // 排除后为空，说明排除与歌手条件在同一条 SQL 里共同生效（没有退化成「忽略歌手条件」）
        assertTrue(songQuery.searchExcluding(null, "测试歌手B", null, List.of(13L, 14L, 20L),
                ExclusionConstraints.none(), 10).isEmpty());
    }

    @Test
    void artistSearchToolReportsCooperationSong() throws Exception {
        insertCooperationSong();

        RecordingSink sink = new RecordingSink();
        String result = readTools(sink, 1L, "c1", new AgentMetrics("t1", 1L, "c1", "test"))
                .searchSongsByArtist("测试歌手B");

        var tree = new ObjectMapper().readTree(result);
        assertTrue(tree.path("ok").asBoolean());
        assertEquals(3, tree.path("total").asInt());
        assertEquals("测试歌手B", tree.path("search").path("artistName").asText());
    }
}
