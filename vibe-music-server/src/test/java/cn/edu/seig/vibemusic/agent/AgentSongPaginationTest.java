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

    /**
     * 推荐类工具默认只返回 5 首。
     *
     * <p><b>为什么这是一个必须锁住的契约：</b>用户说「推荐几首」时，10 首会让模型倾向于把整份结果
     * 复述一遍，界面也要翻两页；而「几首」在中文里本来就是 3~5 的量级。
     * 默认值写在 Java 里而不是提示词里，是因为提示词里的数字会被模型选择性忽略（旧版 18 条规则
     * 只稳定执行了 2 条），而工具签名里的默认值一定生效。
     *
     * <p>同时验证「用户明确要更多」时仍然可控：传 limit 就能突破默认值，上限仍是 20。
     */
    @Test
    void recommendToolsDefaultToFiveSongsAndHonourExplicitLimit() throws Exception {
        // 夹具里歌手A只有 3 首，不足以区分 5 与 10，先补到 8 首。
        for (int i = 200; i < 205; i++) {
            jdbc.update("INSERT INTO tb_song(id,name,artist_id,artist_names,style) VALUES (?,?,1,'测试歌手A','流行')", i, "补歌" + i);
            jdbc.update("INSERT INTO tb_song_artist(song_id, artist_id, display_order) VALUES (?,1,1)", i);
        }

        var tools = readTools(new RecordingSink(), 1L, "c1", new AgentMetrics("t1", 1L, "c1", "m"));

        assertEquals(5, songIdsOf(tools.searchSongsByArtist("测试歌手A", null)).size(),
                "不传 limit 时应返回默认 5 首（该歌手共 8 首）");
        assertEquals(5, songIdsOf(tools.searchSongsByArtist("测试歌手A", "")).size(),
                "空串与 null 同样走默认值");
        assertEquals(5, songIdsOf(tools.getSongsByStyle("流行", null)).size());
        assertEquals(5, songIdsOf(tools.getRandomSongs(null)).size());
        // 用户明确要 8 首 → 按他说的给，不被默认值压住。
        assertEquals(8, songIdsOf(tools.searchSongsByArtist("测试歌手A", "8")).size());
        // 上限仍是 20，非法值回落默认值而不是报错。
        assertEquals(8, songIdsOf(tools.searchSongsByArtist("测试歌手A", "999")).size(), "上限 20，但只有 8 首");
        assertEquals(5, songIdsOf(tools.searchSongsByArtist("测试歌手A", "abc")).size(), "非法值回落默认 5");
    }

    private static java.util.List<Long> songIdsOf(String raw) throws Exception {
        return cn.edu.seig.vibemusic.model.agent.AgentJson.MAPPER
                .readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<
                        cn.edu.seig.vibemusic.model.agent.ToolResult<java.util.List<cn.edu.seig.vibemusic.model.agent.SongRef>>>() { })
                .data().stream().map(cn.edu.seig.vibemusic.model.agent.SongRef::songId).toList();
    }

    @Test
    void liveEventsCarryTheSameSearchCriteriaAsPersistedResults() throws Exception {
        RecordingSink sink = new RecordingSink();
        String result = readTools(sink, 1L, "c1", new AgentMetrics("t1", 1L, "c1", "test"))
                .searchSongsByArtist("测试歌手A", null);
        assertEquals("测试歌手A", sink.firstOfType(AgentEvent.SONGS).search().artistName());
        assertEquals("测试歌手A", new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result).path("search").path("artistName").asText());
    }
}
