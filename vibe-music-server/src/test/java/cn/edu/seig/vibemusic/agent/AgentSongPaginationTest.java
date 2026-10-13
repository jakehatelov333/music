package cn.edu.seig.vibemusic.agent;

import cn.edu.seig.vibemusic.model.agent.AgentEvent;
import cn.edu.seig.vibemusic.service.agent.AgentMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class AgentSongPaginationTest extends AbstractAgentTest {
    @Test
    void pagesKeepFiltersAndReturnFullCountBeyondToolLimit() {
        for (int i = 100; i < 125; i++) {
            // artist_names 与 tb_song_artist 必须成对维护：前者供列表展示，后者是歌手归属的唯一权威来源。
            jdbc.update("INSERT INTO tb_song(id,name,artist_id,artist_names,style) VALUES (?,?,1,'测试歌手A','流行')", i, "新增歌曲" + i);
            jdbc.update("INSERT INTO tb_song_artist(song_id, artist_id, display_order) VALUES (?,1,1)", i);
        }
        var first = songQuery.page(null, " 测试歌手A ", null, 1, 5);
        var last = songQuery.page(null, "测试歌手A", null, 6, 5);
        assertEquals(28, first.total());
        assertEquals(5, first.songs().size());
        assertEquals(3, last.songs().size());
        assertEquals(122L, last.songs().get(0).songId());
        assertTrue(last.songs().stream().allMatch(s -> s.artistName().equals("测试歌手A")));
        assertTrue(first.songs().stream().noneMatch(last.songs()::contains));
        assertEquals(2, songQuery.page("测试专辑A", "测试歌手A", "流行", 1, 5).total());
    }

    @Test
    void emptyAndOutOfRangePagesAreExplicitAndBadParametersAreRejected() {
        assertEquals(0, songQuery.page("不存在", null, null, 1, 5).total());
        var beyond = songQuery.page(null, "测试歌手A", null, 10, 5);
        assertEquals(3, beyond.total());
        assertTrue(beyond.songs().isEmpty());
        assertThrows(ResponseStatusException.class, () -> songQuery.page(null, null, null, 1, 5));
        assertThrows(ResponseStatusException.class, () -> songQuery.page("夜", null, null, 0, 5));
        assertThrows(ResponseStatusException.class, () -> songQuery.page("夜", null, null, 1, 21));
    }

    @Test
    void liveEventsCarryTheSameSearchCriteriaAsPersistedResults() throws Exception {
        RecordingSink sink = new RecordingSink();
        String result = readTools(sink, 1L, "c1", new AgentMetrics("t1", 1L, "c1", "test"))
                .searchSongsByArtist("测试歌手A");
        assertEquals("测试歌手A", sink.firstOfType(AgentEvent.SONGS).search().artistName());
        assertEquals("测试歌手A", new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result).path("search").path("artistName").asText());
    }
}
