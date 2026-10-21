package cn.edu.seig.vibemusic.agent;

import cn.edu.seig.vibemusic.model.agent.*;
import cn.edu.seig.vibemusic.model.vo.PendingActionVO;
import cn.edu.seig.vibemusic.service.agent.AgentMetrics;
import cn.edu.seig.vibemusic.tool.PlaylistActionTools;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 定向修复验收：连续创建同类歌单时不再机械重复选曲。
 *
 * <p>断言全部落在<b>真实数据库与真实工具返回值</b>上（歌曲 ID 集合、待确认任务行数、状态码），
 * 而不是模型文本或歌单名称 —— 后者变化了也不代表问题被修好。
 *
 * <p>覆盖五组：
 * <ol>
 *     <li>连续创建：第二次候选在 SQL 层排除第一次用过的歌，且不会因原有 LIMIT 误判「没有新歌」；</li>
 *     <li>历史隔离：不同用户、不同会话互不影响；无历史时与普通检索一致；</li>
 *     <li>用户意图：显式复用可用、指定歌曲不被误删、普通搜索不受影响、新歌不足不编造；</li>
 *     <li>数据与状态：损坏预览容错、集合规范化比较、不破坏既有待确认流程；</li>
 *     <li>回归：确认/幂等/越权等既有语义不受本次改动影响。</li>
 * </ol>
 */
class PlaylistSelectionDiversityTest extends AbstractAgentTest {

    private static final String CHAT = "chat-diversity";

    // ================================================================ 1. 连续创建

    @Test
    @DisplayName("同一会话连续创建：第二次候选在数据库层排除第一次用过的歌，且不会因原有 LIMIT 误判无歌")
    void secondCandidateSearchExcludesFirstSelectionBeyondLimit() throws Exception {
        // 「测试歌手A」在夹具里有 10/11/12 三首，再补 25 首（100~124），共 28 首。
        // 28 > 20（单次工具上限），因此能精确验证「先排除再 LIMIT」而不是「先 LIMIT 再过滤」。
        insertSongsByArtist(1L, 100, 124);

        ToolResult<List<SongRef>> first = candidates(1L, CHAT, "测试歌手A", "", "", "20");
        assertEquals(AgentToolCodes.OK, first.code(), first.message());
        assertEquals(20, first.data().size(), "首次应取满 20 首");
        List<Long> firstIds = songIdsOf(first.data());

        PendingActionVO firstPreview = prepareViaTool(1L, CHAT, "薛式情歌一", firstIds, null);
        assertEquals(20, firstPreview.getSongCount());
        assertEquals(firstIds, songIdsOf(firstPreview.getSongs()));

        ToolResult<List<SongRef>> second = candidates(1L, CHAT, "测试歌手A", "", "", "20");
        assertEquals(AgentToolCodes.OK, second.code(), second.message());
        List<Long> secondIds = songIdsOf(second.data());
        assertEquals(8, secondIds.size(), "排除已用的 20 首后仍应取到剩余 8 首，而不是空");
        assertTrue(Collections.disjoint(firstIds, secondIds), "第二次候选不得包含第一次用过的歌曲");

        PendingActionVO secondPreview = prepareViaTool(1L, CHAT, "薛式情歌二", secondIds, null);
        assertNotEquals(firstPreview.getTaskId(), secondPreview.getTaskId(), "每轮都应生成独立的待确认任务");
        assertTrue(Collections.disjoint(songIdsOf(firstPreview.getSongs()), songIdsOf(secondPreview.getSongs())),
                "两次预览的歌曲集合必须不同");
        assertEquals(2, count("tb_agent_pending_action"));
        assertEquals(0, count("tb_playlist"), "两次都只出预览，确认前不得写入歌单");
    }

    @Test
    @DisplayName("候选工具返回的是数据库里真实存在的歌曲，且带分页所需的检索条件")
    void candidateToolReturnsRealSongsWithSearchCriteria() throws Exception {
        RecordingSink sink = new RecordingSink();
        AgentMetrics metrics = new AgentMetrics("t", 1L, CHAT, "m");
        String raw = readTools(sink, 1L, CHAT, metrics).searchPlaylistCandidates("测试歌手A", "", "", null);

        ToolResult<List<SongRef>> result = AgentJson.MAPPER.readValue(raw,
                new TypeReference<ToolResult<List<SongRef>>>() { });
        assertTrue(result.ok());
        assertEquals(AgentToolKinds.SONG_LIST, result.kind());
        assertEquals(3, result.data().size());
        assertEquals("测试歌手A", result.search().artistName());
        assertEquals(1, metrics.toolCalls());
        assertEquals(0, metrics.toolFailures());

        List<Long> persisted = jdbc.queryForList("SELECT id FROM tb_song ORDER BY id", Long.class);
        assertTrue(persisted.containsAll(songIdsOf(result.data())), "候选必须是库里真实存在的歌曲");

        AgentEvent songs = sink.firstOfType(AgentEvent.SONGS);
        assertNotNull(songs, "候选歌曲同样应推送 songs 事件供前端渲染卡片");
        assertEquals(result.total(), songs.total());
    }

    @Test
    @DisplayName("三个筛选条件全为空时是参数错误，不是「没有新歌」")
    void blankCriteriaIsInvalidArgument() throws Exception {
        RecordingSink sink = new RecordingSink();
        AgentMetrics metrics = new AgentMetrics("t", 1L, CHAT, "m");
        String raw = readTools(sink, 1L, CHAT, metrics).searchPlaylistCandidates("  ", "", null, null);
        ToolResult<?> result = AgentJson.MAPPER.readValue(raw, new TypeReference<ToolResult<?>>() { });
        assertFalse(result.ok());
        assertEquals(AgentToolCodes.INVALID_ARGUMENT, result.code());
        assertEquals(1, metrics.toolFailures());
    }

    // ================================================================ 2. 历史隔离

    @Test
    @DisplayName("选曲历史严格按用户与会话隔离：换会话或换用户都不受影响")
    void historyIsIsolatedByUserAndChat() throws Exception {
        prepareViaTool(1L, CHAT, "隔离测试", ids(10, 11), null);

        List<Long> sameScope = candidateIds(1L, CHAT, "测试歌手A");
        assertFalse(sameScope.contains(10L), "同用户同会话：已用过的歌应被排除");
        assertFalse(sameScope.contains(11L));
        assertTrue(candidateIds(1L, "另一个会话", "测试歌手A").containsAll(ids(10, 11)),
                "同用户不同会话不得共享历史");
        assertTrue(candidateIds(2L, CHAT, "测试歌手A").containsAll(ids(10, 11)),
                "不同用户不得共享历史");

        Set<Long> user1 = selectionHistory.recentSongIds(1L, CHAT);
        assertEquals(Set.of(10L, 11L), user1);
        assertTrue(selectionHistory.recentSongIds(1L, "另一个会话").isEmpty());
        assertTrue(selectionHistory.recentSongIds(2L, CHAT).isEmpty());
    }

    @Test
    @DisplayName("没有历史记录时，候选检索结果与普通检索完全一致")
    void withoutHistoryCandidatesMatchPlainSearch() throws Exception {
        List<Long> plain = songIdsOf(songQuery.search(null, "测试歌手A", null, null, 20));
        assertEquals(plain, candidateIds(1L, CHAT, "测试歌手A"));
    }

    @Test
    @DisplayName("游客没有选曲历史，候选检索照常返回歌曲")
    void guestCandidatesWorkWithoutHistory() throws Exception {
        ToolResult<List<SongRef>> result = candidates(null, CHAT, "测试歌手A", "", "", null);
        assertTrue(result.ok(), result.message());
        assertEquals(3, result.data().size());
    }

    @Test
    @DisplayName("只参考最近 N 条预览：更早的历史会滚出窗口，歌曲重新可用")
    void historyWindowIsBoundedByLookback() throws Exception {
        // 依次建 6 份互不相同的预览；lookback=5 时只有最近 5 份参与排除。
        prepareViaTool(1L, CHAT, "窗口1", ids(10), null);
        prepareViaTool(1L, CHAT, "窗口2", ids(11), null);
        prepareViaTool(1L, CHAT, "窗口3", ids(12), null);
        prepareViaTool(1L, CHAT, "窗口4", ids(13), null);
        prepareViaTool(1L, CHAT, "窗口5", ids(14), null);
        prepareViaTool(1L, CHAT, "窗口6", ids(11, 12), null);
        assertEquals(6, count("tb_agent_pending_action"));

        assertEquals(5, selectionHistory.recentSongSets(1L, CHAT).size(), "最多只读 lookback 条预览");
        // 只有最早那份里的 10 号歌滚出了窗口；11/12/13/14 仍在窗口内。
        assertEquals(List.of(10L), candidateIds(1L, CHAT, null, "测试专辑A", "", "20"));
    }

    // ================================================================ 3. 用户意图

    @Test
    @DisplayName("用户明确要求复用旧歌：完全相同的歌单仍可生成预览，且是新的待确认任务")
    void explicitReuseIsStillAllowed() throws Exception {
        PendingActionVO first = prepareViaTool(1L, CHAT, "原方案", ids(10, 11), null);

        ToolResult<PendingActionVO> blocked = prepareRaw(1L, CHAT, "重复方案", ids(10, 11), null);
        assertFalse(blocked.ok(), "未声明复用时，完全相同的歌曲集合应被拒绝");
        assertEquals(AgentToolCodes.REJECTED, blocked.code());
        assertTrue(blocked.message().contains("完全相同"), blocked.message());
        // 拒绝消息是模型唯一的补救线索：必须明确写出下一步该调用什么工具、以及复用开关叫什么。
        // 实测教训：早期措辞只说"请换一批候选歌曲"，真实模型会停下来反问用户，用户拿不到任何卡片。
        assertTrue(blocked.message().contains("searchPlaylistCandidates"),
                "拒绝消息必须指明补救工具：" + blocked.message());
        assertTrue(blocked.message().contains("allowReuse"),
                "拒绝消息必须指明显式复用开关：" + blocked.message());
        assertTrue(blocked.message().contains("不要停下来询问用户"),
                "拒绝消息必须阻止模型把任务推回用户：" + blocked.message());

        // 顺序不同也算同一集合：规范化后比较。
        PendingActionVO reused = prepareViaTool(1L, CHAT, "沿用原方案", ids(11, 10), "true");
        assertEquals(2, reused.getSongCount());
        assertNotEquals(first.getTaskId(), reused.getTaskId(), "复用也要生成独立任务，不能复用上一轮任务 ID");
        assertEquals(2, count("tb_agent_pending_action"));
        assertEquals(0, count("tb_playlist"));
    }

    @Test
    @DisplayName("服务端只拦「整份重复」：部分重合、增删歌曲都不受影响")
    void onlyIdenticalSongSetsAreRejected() throws Exception {
        prepareViaTool(1L, CHAT, "基准歌单", ids(10, 11), null);

        assertTrue(prepareRaw(1L, CHAT, "整份重复", ids(10, 11), null).ok() == false, "整份相同应被拒绝");
        assertTrue(prepareRaw(1L, CHAT, "多一首", ids(10, 11, 12), null).ok(), "增删歌曲不算重复");
        assertTrue(prepareRaw(1L, CHAT, "只留一首", ids(10), null).ok(), "部分重合不算重复");
        assertTrue(prepareRaw(1L, CHAT, "换成另一首", ids(13, 14), null).ok());
        // 上一步的「只留一首」现在成了历史，再提同一首就属于整份重复。
        assertFalse(prepareRaw(1L, CHAT, "又一次只留一首", ids(10), null).ok());
    }

    @Test
    @DisplayName("普通搜索不受历史影响：已进过歌单的歌仍然搜得到")
    void plainSearchStillReturnsSongsAlreadyUsedInPlaylists() throws Exception {
        prepareViaTool(1L, CHAT, "已用歌单", ids(10, 11), null);

        RecordingSink sink = new RecordingSink();
        var tools = readTools(sink, 1L, CHAT, new AgentMetrics("t", 1L, CHAT, "m"));

        assertTrue(songIdsOfJson(tools.searchSongs("夜色", null)).contains(10L), "普通搜索不得被历史过滤");
        assertTrue(songIdsOfJson(tools.searchSongsByArtist("测试歌手A", null)).containsAll(ids(10, 11)));
        assertTrue(songIdsOfJson(tools.getSongsByStyle("流行", null)).containsAll(ids(10, 11)));
        assertTrue(songIdsOfJson(tools.searchSongs("测试专辑A", null)).containsAll(ids(10, 11, 12)));

        // 对照：候选检索则确实排除了它们。
        assertFalse(candidateIds(1L, CHAT, "测试歌手A").contains(10L));
    }

    @Test
    @DisplayName("用户点名要的歌不会被历史排除机制删掉")
    void explicitlyRequestedSongIsKept() throws Exception {
        prepareViaTool(1L, CHAT, "第一次", ids(10, 11), null);
        // 用户明确要求保留 10 号歌，并补一首新的 12 号歌 → 集合不同，直接放行。
        PendingActionVO second = prepareViaTool(1L, CHAT, "保留指定歌曲", ids(10, 12), null);
        assertEquals(List.of(10L, 12L), songIdsOf(second.getSongs()));
    }

    @Test
    @DisplayName("新歌不足时如实返回 EMPTY 并提示复用，不编造歌曲、不返回旧歌充数")
    void shortageIsReportedWithoutFabrication() throws Exception {
        // 「测试歌手B」在夹具里只有 13、14 两首，先用光。
        prepareViaTool(1L, CHAT, "粤语精选", ids(13, 14), null);

        ToolResult<List<SongRef>> result = candidates(1L, CHAT, "测试歌手B", "", "", "10");
        assertTrue(result.ok(), "「没有新歌」是查询成功但没有命中，不是工具失败");
        assertEquals(AgentToolCodes.EMPTY, result.code());
        assertEquals(0, result.total());
        assertNull(result.data(), "不得为了让数量好看而返回已用过的歌");
        assertTrue(result.message().contains("复用"), "应明确告知可用新歌不足并引导复用：" + result.message());

        // 真正没有这个歌手的歌时，提示的是「未找到」，两种情况可区分。
        ToolResult<List<SongRef>> none = candidates(1L, CHAT, "不存在的歌手", "", "", "10");
        assertEquals(AgentToolCodes.EMPTY, none.code());
        assertTrue(none.message().contains("未找到"), none.message());
    }

    // ================================================================ 4. 数据与状态

    @Test
    @DisplayName("损坏的历史预览被跳过并记录日志，不会让整个对话失败")
    void corruptedPreviewJsonIsTolerated() throws Exception {
        PendingActionVO preview = prepareViaTool(1L, CHAT, "损坏测试", ids(10), null);
        jdbc.update("UPDATE tb_agent_pending_action SET preview_json = '{ this is not json' WHERE task_id = ?",
                preview.getTaskId());

        assertTrue(selectionHistory.recentSongSets(1L, CHAT).isEmpty(), "无法解析的预览应被跳过");
        assertTrue(selectionHistory.recentSongIds(1L, CHAT).isEmpty());
        assertFalse(selectionHistory.isDuplicateOfRecent(1L, CHAT, ids(10)));

        // 候选检索照常工作（只是这份损坏历史没能参与排除）。
        ToolResult<List<SongRef>> result = candidates(1L, CHAT, "测试歌手A", "", "", null);
        assertEquals(AgentToolCodes.OK, result.code());
        assertTrue(songIdsOf(result.data()).contains(10L));
    }

    @Test
    @DisplayName("preview_json 缺少歌曲字段时同样容错")
    void previewWithoutSongsIsTolerated() throws Exception {
        PendingActionVO preview = prepareViaTool(1L, CHAT, "空歌曲字段", ids(10), null);
        jdbc.update("UPDATE tb_agent_pending_action SET preview_json = '{\"title\":\"x\",\"songCount\":0}' "
                + "WHERE task_id = ?", preview.getTaskId());
        assertTrue(selectionHistory.recentSongSets(1L, CHAT).isEmpty());
        assertEquals(AgentToolCodes.OK, candidates(1L, CHAT, "测试歌手A", "", "", null).code());
    }

    @Test
    @DisplayName("allowReuse 只认明确的正向写法，留空或缺失一律按不复用处理")
    void allowReuseIsParsedStrictly() {
        assertTrue(PlaylistActionTools.parseAllowReuse("true"));
        assertTrue(PlaylistActionTools.parseAllowReuse(" TRUE "));
        assertTrue(PlaylistActionTools.parseAllowReuse("1"));
        assertFalse(PlaylistActionTools.parseAllowReuse(null));
        assertFalse(PlaylistActionTools.parseAllowReuse(""));
        assertFalse(PlaylistActionTools.parseAllowReuse("false"));
        assertFalse(PlaylistActionTools.parseAllowReuse("也许吧"));
    }

    @Test
    @DisplayName("选曲历史只统计创建歌单类型的任务，不受其他会话的待确认任务干扰")
    void historyIgnoresOtherActionTypes() throws Exception {
        prepareViaTool(1L, CHAT, "正常歌单", ids(10), null);
        jdbc.update("UPDATE tb_agent_pending_action SET action_type = 'OTHER_ACTION'");

        assertTrue(selectionHistory.recentSongIds(1L, CHAT).isEmpty());
        assertEquals(AgentToolCodes.OK, candidates(1L, CHAT, "测试歌手A", "", "", null).code());
    }

    // ================================================================ 5. 回归

    @Test
    @DisplayName("回归：确认流程、幂等与越权隔离在去重改造后保持不变")
    void existingPendingActionSemanticsSurvive() throws Exception {
        login(1L);
        PendingActionVO preview = prepareViaTool(1L, CHAT, "回归歌单", ids(10, 11), null);

        assertEquals(0, count("tb_playlist"), "确认前不得写入");
        PendingActionVO done = pendingActions.confirm(preview.getTaskId(), preview.getVersion());
        assertEquals("SUCCEEDED", done.getStatus());
        assertEquals(1, count("tb_playlist"));
        assertEquals(2, count("tb_playlist_binding"));

        // 重复确认仍然幂等。
        assertEquals(done.getPlaylistId(), pendingActions.confirm(preview.getTaskId(), null).getPlaylistId());
        assertEquals(1, count("tb_playlist"));

        // 越权仍然一律 404。
        login(2L);
        var denied = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> pendingActions.get(preview.getTaskId()));
        assertEquals(404, denied.getStatusCode().value());
    }

    @Test
    @DisplayName("回归：去重发生在写库之前，被拒绝时不留下任何待确认记录")
    void rejectedDuplicateLeavesNoTrace() throws Exception {
        prepareViaTool(1L, CHAT, "第一次", ids(10), null);
        assertEquals(1, count("tb_agent_pending_action"));

        assertFalse(prepareRaw(1L, CHAT, "重复", ids(10), null).ok());
        assertEquals(1, count("tb_agent_pending_action"), "被拒绝的请求不得留下半成品任务");
        assertEquals(0, count("tb_playlist"));
    }

    // ================================================================ helpers

    /** 直接调用候选工具并解析结构化返回。 */
    private ToolResult<List<SongRef>> candidates(Long userId, String chatId, String artist, String keyword,
                                                 String style, String limit) throws Exception {
        RecordingSink sink = new RecordingSink();
        String raw = readTools(sink, userId, chatId, new AgentMetrics("t", userId, chatId, "m"))
                .searchPlaylistCandidates(artist, keyword, style, limit);
        return AgentJson.MAPPER.readValue(raw, new TypeReference<ToolResult<List<SongRef>>>() { });
    }

    private List<Long> candidateIds(Long userId, String chatId, String artist) throws Exception {
        return songIdsOf(candidates(userId, chatId, artist, "", "", null).data());
    }

    private List<Long> candidateIds(Long userId, String chatId, String artist, String keyword, String style,
                                    String limit) throws Exception {
        return songIdsOf(candidates(userId, chatId, artist, keyword, style, limit).data());
    }

    /** 通过真实工具生成预览，断言成功并返回预览。 */
    private PendingActionVO prepareViaTool(Long userId, String chatId, String title, List<Long> songIds,
                                          String allowReuse) throws Exception {
        ToolResult<PendingActionVO> result = prepareRaw(userId, chatId, title, songIds, allowReuse);
        assertTrue(result.ok(), "预览应生成成功：" + result.code() + " / " + result.message());
        assertNotNull(result.data());
        return result.data();
    }

    private ToolResult<PendingActionVO> prepareRaw(Long userId, String chatId, String title, List<Long> songIds,
                                                   String allowReuse) throws Exception {
        RecordingSink sink = new RecordingSink();
        String csv = songIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        String raw = actionTools(sink, userId, chatId, new AgentMetrics("t", userId, chatId, "m"))
                .preparePlaylistCreation(title, "定向修复测试", "流行", csv, allowReuse);
        return AgentJson.MAPPER.readValue(raw, new TypeReference<ToolResult<PendingActionVO>>() { });
    }

    private List<Long> songIdsOfJson(String raw) throws Exception {
        ToolResult<List<SongRef>> result = AgentJson.MAPPER.readValue(raw,
                new TypeReference<ToolResult<List<SongRef>>>() { });
        return songIdsOf(result.data());
    }

    private static List<Long> songIdsOf(List<SongRef> songs) {
        if (songs == null) return List.of();
        return songs.stream().map(SongRef::songId).toList();
    }

    private void insertSongsByArtist(Long artistId, long fromId, long toId) {
        // 展示串必须与关系表一致（artistId=1 → 测试歌手A，2 → 测试歌手B，见 AbstractAgentTest 夹具）。
        String artistName = artistId != null && artistId == 2L ? "测试歌手B" : "测试歌手A";
        List<Object[]> songs = new ArrayList<>();
        List<Object[]> relations = new ArrayList<>();
        for (long id = fromId; id <= toId; id++) {
            songs.add(new Object[]{id, "曲库歌曲" + id, artistId, artistName});
            relations.add(new Object[]{id, artistId});
        }
        jdbc.batchUpdate("INSERT INTO tb_song(id,name,artist_id,artist_names,style) VALUES (?,?,?,?,'流行')", songs);
        jdbc.batchUpdate("INSERT INTO tb_song_artist(song_id, artist_id, display_order) VALUES (?,?,1)", relations);
    }
}
