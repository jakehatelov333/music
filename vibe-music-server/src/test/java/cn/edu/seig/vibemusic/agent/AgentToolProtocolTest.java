package cn.edu.seig.vibemusic.agent;

import cn.edu.seig.vibemusic.model.agent.*;
import cn.edu.seig.vibemusic.service.agent.AgentMetrics;
import cn.edu.seig.vibemusic.tool.MusicTools;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 第 9 项验收：统一工具返回协议、结构化歌曲数据、事件关联、可信用户上下文。
 *
 * <p>这里对工具返回值做<b>真实的反序列化</b>，而不是只看 Java 能否编译 ——
 * 因为 LangChain4j 0.36.2 对工具返回值的处理路径会因返回类型不同而不同，
 * 必须证明我们选择的 String(JSON) 形态在框架里是可解析的。
 */
class AgentToolProtocolTest extends AbstractAgentTest {

    private final ObjectMapper json = new ObjectMapper();

    private <T> ToolResult<T> parse(String raw) throws Exception {
        return json.readValue(raw, new TypeReference<>() { });
    }

    @Test
    @DisplayName("搜索工具返回可被 Jackson 反序列化的结构化 JSON，且包含真实歌曲 ID、封面与播放地址")
    void searchToolReturnsStructuredJsonWithRealSongData() throws Exception {
        RecordingSink sink = new RecordingSink();
        AgentMetrics metrics = new AgentMetrics("t1", 1L, "c1", "test-model");
        String raw = readTools(sink, 1L, "c1", metrics).searchSongs("夜色", null);

        ToolResult<List<SongRef>> result = json.readValue(raw, new TypeReference<>() { });
        assertTrue(result.ok());
        assertEquals(AgentToolCodes.OK, result.code());
        assertEquals(AgentToolKinds.SONG_LIST, result.kind());
        assertEquals(1, result.total());
        assertEquals(1, result.data().size());

        SongRef song = result.data().get(0);
        assertEquals(10L, song.songId());
        assertEquals("夜色温柔", song.songName());
        assertEquals("测试歌手A", song.artistName());
        assertEquals("http://minio/10.jpg", song.coverUrl());
        assertEquals("http://minio/10.mp3", song.audioUrl());
        assertEquals("03:20", song.duration());
        assertEquals("流行", song.style());
        assertEquals(1, metrics.toolCalls());
        assertEquals(0, metrics.toolFailures());
    }

    @Test
    @DisplayName("搜索成功但没有结果 = ok/EMPTY，不等同于工具调用失败")
    void emptySearchIsSuccessNotFailure() throws Exception {
        RecordingSink sink = new RecordingSink();
        String raw = readTools(sink, 1L, "c1", new AgentMetrics("t1", 1L, "c1", "m"))
                .searchSongs("完全不存在的曲目名", null);

        ToolResult<?> result = parse(raw);
        assertTrue(result.ok(), "没搜到结果不是失败");
        assertEquals(AgentToolCodes.EMPTY, result.code());
        assertEquals(0, result.total());
        assertNull(result.data());
        assertTrue(result.message().contains("未找到"));
    }

    @Test
    @DisplayName("参数非法 = ok=false/INVALID_ARGUMENT，模型可据此修正参数重试")
    void invalidArgumentIsCorrectableFailure() throws Exception {
        RecordingSink sink = new RecordingSink();
        AgentMetrics metrics = new AgentMetrics("t1", 1L, "c1", "m");
        ToolResult<?> result = parse(readTools(sink, 1L, "c1", metrics).searchSongs("   ", null));
        assertFalse(result.ok());
        assertEquals(AgentToolCodes.INVALID_ARGUMENT, result.code());
        assertEquals(1, metrics.toolFailures());
    }

    @Test
    @DisplayName("工具开始/结束/结构化数据三类事件通过同一个 toolCallId 关联，且带真实耗时")
    void toolEventsShareOneCorrelatedId() {
        RecordingSink sink = new RecordingSink();
        readTools(sink, 1L, "c1", new AgentMetrics("t1", 1L, "c1", "m")).searchSongs("夜色", null);

        List<AgentEvent> tools = sink.ofType(AgentEvent.TOOL);
        assertEquals(2, tools.size(), "一次调用应产生 start 与 end 两个事件");
        assertEquals(AgentEvent.PHASE_START, tools.get(0).phase());
        assertEquals(AgentEvent.PHASE_END, tools.get(1).phase());
        assertEquals("searchSongs", tools.get(0).tool());
        assertEquals(tools.get(0).toolCallId(), tools.get(1).toolCallId());
        assertEquals("task-c1", tools.get(0).taskId());
        assertNotNull(tools.get(1).durationMs());
        assertTrue(tools.get(1).ok());

        AgentEvent songs = sink.firstOfType(AgentEvent.SONGS);
        assertNotNull(songs, "有结构化歌曲时应推送 songs 事件");
        assertEquals(tools.get(1).toolCallId(), songs.toolCallId());
        assertEquals(AgentToolKinds.SONG_LIST, songs.kind());
        assertEquals(1, songs.total());
    }

    @Test
    @DisplayName("空结果不推送 songs 事件，避免前端渲染空卡片")
    void emptyResultDoesNotEmitSongsEvent() {
        RecordingSink sink = new RecordingSink();
        readTools(sink, 1L, "c1", new AgentMetrics("t1", 1L, "c1", "m")).searchSongs("不存在的曲目", null);
        assertNull(sink.firstOfType(AgentEvent.SONGS));
        assertEquals(2, sink.ofType(AgentEvent.TOOL).size());
    }

    @Test
    @DisplayName("游客调用写工具得到 UNAUTHORIZED，且不产生任何待确认记录")
    void guestCannotPreparePlaylist() throws Exception {
        RecordingSink sink = new RecordingSink();
        AgentMetrics metrics = new AgentMetrics("t1", null, "c1", "m");
        String raw = actionTools(sink, null, "c1", metrics).preparePlaylistCreation(
                "夜间驾驶", "", "流行", "10,11", null);

        ToolResult<?> result = parse(raw);
        assertFalse(result.ok());
        assertEquals(AgentToolCodes.UNAUTHORIZED, result.code());
        assertEquals(0, count("tb_agent_pending_action"), "游客不得产生待确认任务");
        assertNull(sink.firstOfType(AgentEvent.CONFIRM), "未登录不得推送确认卡片");
        assertEquals(1, metrics.toolFailures());
    }

    @Test
    @DisplayName("写工具只生成待确认任务，绝不创建歌单；预览数据来自真实数据库")
    void prepareCreatesPendingActionOnly() throws Exception {
        RecordingSink sink = new RecordingSink();
        AgentMetrics metrics = new AgentMetrics("t1", 1L, "c1", "m");
        String raw = actionTools(sink, 1L, "c1", metrics).preparePlaylistCreation(
                "夜间驾驶", "适合开车听", "流行", "10, 11", null);

        ToolResult<?> result = parse(raw);
        assertTrue(result.ok(), result.message());
        assertEquals(AgentToolKinds.PLAYLIST_PREVIEW, result.kind());
        assertEquals(2, result.total());

        assertEquals(1, count("tb_agent_pending_action"));
        assertEquals(0, count("tb_playlist"), "确认之前不允许创建歌单");
        assertEquals(0, count("tb_playlist_binding"));

        AgentEvent confirm = sink.firstOfType(AgentEvent.CONFIRM);
        assertNotNull(confirm, "应推送确认事件供前端渲染确认卡片");
        var preview = (cn.edu.seig.vibemusic.model.vo.PendingActionVO) confirm.data();
        assertEquals("夜间驾驶", preview.getTitle());
        assertEquals(2, preview.getSongCount());
        assertEquals(List.of(10L, 11L), preview.getSongs().stream().map(SongRef::songId).toList());
        assertEquals("http://minio/10.jpg", preview.getSongs().get(0).coverUrl());
        assertEquals("PENDING", preview.getStatus());
    }

    @Test
    @DisplayName("模型编造不存在的歌曲 ID 时被服务端拒绝，不落待确认记录")
    void fabricatedSongIdsAreRejected() throws Exception {
        RecordingSink sink = new RecordingSink();
        String raw = actionTools(sink, 1L, "c1", new AgentMetrics("t1", 1L, "c1", "m"))
                .preparePlaylistCreation("假歌单", "", "流行", "10,999999", null);

        ToolResult<?> result = parse(raw);
        assertFalse(result.ok());
        assertEquals(AgentToolCodes.REJECTED, result.code());
        assertEquals(0, count("tb_agent_pending_action"));
        assertEquals(0, count("tb_playlist"));
    }

    @Test
    @DisplayName("songIds 兼容 CSV / JSON 数组 / 空格分隔三种写法并去重")
    void songIdsFormatsAreParsedLeniently() {
        assertEquals(List.of(10L, 11L), cn.edu.seig.vibemusic.tool.PlaylistActionTools.parseSongIds("10,11"));
        assertEquals(List.of(10L, 11L), cn.edu.seig.vibemusic.tool.PlaylistActionTools.parseSongIds("[10, 11]"));
        assertEquals(List.of(10L, 11L), cn.edu.seig.vibemusic.tool.PlaylistActionTools.parseSongIds("10 11"));
        assertEquals(List.of(10L, 11L), cn.edu.seig.vibemusic.tool.PlaylistActionTools.parseSongIds("10,10,11"));
        assertEquals(List.of(), cn.edu.seig.vibemusic.tool.PlaylistActionTools.parseSongIds("abc"));
        assertEquals(List.of(), cn.edu.seig.vibemusic.tool.PlaylistActionTools.parseSongIds(null));
    }

    @Test
    @DisplayName("只读工具（歌手/歌单）同样返回结构化协议，且歌单结果不泄漏草稿")
    void readOnlyToolsFollowTheSameProtocol() throws Exception {
        jdbc.update("INSERT INTO tb_playlist(title, style, is_public, status, owner_user_id) "
                + "VALUES ('公开歌单','流行',TRUE,'PUBLISHED',NULL)");
        jdbc.update("INSERT INTO tb_playlist(title, style, is_public, status, owner_user_id) "
                + "VALUES ('公开歌单草稿','流行',TRUE,'DRAFT',NULL)");
        RecordingSink sink = new RecordingSink();
        var tools = readTools(sink, 1L, "c1", new AgentMetrics("t1", 1L, "c1", "m"));

        ToolResult<List<PlaylistRef>> found = json.readValue(tools.searchPlaylists("公开歌单"),
                new TypeReference<>() { });
        assertTrue(found.ok());
        assertEquals(AgentToolKinds.PLAYLIST_LIST, found.kind());
        assertEquals(1, found.total(), "草稿不得通过 Agent 泄漏");
        assertEquals("公开歌单", found.data().get(0).title());

        ToolResult<List<ArtistRef>> artists = json.readValue(tools.searchArtists("测试歌手A"),
                new TypeReference<>() { });
        assertTrue(artists.ok());
        assertEquals(AgentToolKinds.ARTIST_LIST, artists.kind());
        assertEquals(1, artists.total());
    }

    @Test
    @DisplayName("工具执行异常被转换成 ok=false/ERROR，不把异常抛回模型循环")
    void toolExceptionBecomesStructuredError() throws Exception {
        // 让底层查询抛异常，验证工具外壳会把它转成结构化失败而不是冒泡出去。
        cn.edu.seig.vibemusic.service.agent.AgentSongQueryService broken =
                org.mockito.Mockito.mock(cn.edu.seig.vibemusic.service.agent.AgentSongQueryService.class);
        org.mockito.Mockito.when(broken.searchByKeyword(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new RuntimeException("database is down"));

        RecordingSink sink = new RecordingSink();
        AgentMetrics metrics = new AgentMetrics("t1", 1L, "c1", "m");
        AgentContext context = AgentContext.of(1L, "c1", "task-c1", sink).withMetrics(metrics);
        MusicTools tools = new MusicTools(broken, artistMapper, playlists, selectionHistory,
                memoryPreferenceService, context);

        ToolResult<?> result = parse(tools.searchSongs("夜色", null));
        assertFalse(result.ok());
        assertEquals(AgentToolCodes.ERROR, result.code());
        assertEquals(1, metrics.toolFailures());
        assertEquals(2, sink.ofType(AgentEvent.TOOL).size(), "异常也要有 start/end 两个事件");
        assertNull(sink.firstOfType(AgentEvent.SONGS));
    }
}
