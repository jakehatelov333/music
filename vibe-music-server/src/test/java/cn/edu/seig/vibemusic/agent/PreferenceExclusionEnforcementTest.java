package cn.edu.seig.vibemusic.agent;

import cn.edu.seig.vibemusic.model.agent.AgentJson;
import cn.edu.seig.vibemusic.model.agent.AgentToolCodes;
import cn.edu.seig.vibemusic.model.agent.SongRef;
import cn.edu.seig.vibemusic.model.agent.ToolResult;
import cn.edu.seig.vibemusic.model.dto.AgentPlaylistDraftDTO;
import cn.edu.seig.vibemusic.model.dto.MemoryPreferenceRequest;
import cn.edu.seig.vibemusic.model.vo.EffectivePreferenceItemVO;
import cn.edu.seig.vibemusic.model.vo.PendingActionVO;
import cn.edu.seig.vibemusic.service.agent.AgentMetrics;
import cn.edu.seig.vibemusic.service.agent.UserMemoryPreferenceService;
import cn.edu.seig.vibemusic.tool.MusicTools;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 结构化偏好排除在「真实执行路径」上的落地验收。
 *
 * <p>本类断言的核心命题：<b>「提示词里告诉模型不要推荐」不等于「候选查询真的过滤了」</b>。
 * 因此所有断言都落在真实 SQL 的结果（歌曲 ID 集合、待确认任务行数、状态码）上，
 * 而不是模型文本 —— 排除必须在数据库层发生，且发生在 {@code LIMIT} 之前。
 *
 * <p>夹具见 {@link AbstractAgentTest}：歌手 1「测试歌手A」（歌 10/11/12，其中 12 为摇滚）、
 * 歌手 2「测试歌手B」（歌 13/14）。需要多值风格 / 合作曲的场景在用例内自行插入。
 */
class PreferenceExclusionEnforcementTest extends AbstractAgentTest {

    private static final String CHAT = "chat-pref-exclusion";

    @Autowired
    UserMemoryPreferenceService memory;

    // ================================================================ 1. 自动画像 vs 手动排除

    @Test
    @DisplayName("自动画像推断喜欢摇滚，但用户手动排除摇滚 → 候选不再含摇滚歌（排除优先于画像）")
    void autoProfileLikesRockButManualExclusionKeepsItOutOfCandidates() throws Exception {
        // 收藏摇滚歌 12 → 自动画像会推断「常听风格：摇滚」。
        jdbc.update("INSERT INTO tb_user_favorite(id,user_id,type,song_id,create_time) "
                + "VALUES (1,1,0,12,CURRENT_TIMESTAMP)");

        // 排除前：摇滚歌 12 正常出现在候选里。
        assertTrue(candidateIds(1L, "测试歌手A", "", "", "20").contains(12L));

        // 手动排除「摇滚」。
        create(1L, "EXCLUDE_STYLE", null, "摇滚");

        List<Long> after = candidateIds(1L, "测试歌手A", "", "", "20");
        assertFalse(after.contains(12L), "手动排除的风格不得因自动画像喜欢而复活");
        assertTrue(after.containsAll(List.of(10L, 11L)), "未被排除的歌曲仍应正常返回");
    }

    // ================================================================ 2. 喜欢 + 排除同项

    @Test
    @DisplayName("手动喜欢某歌手同时排除该歌手 → 排除优先、报告冲突，检索侧取到排除集合")
    void manualLikeAndExcludeSameArtistExclusionWinsAndReportsConflict() throws Exception {
        create(1L, "FAVORITE_ARTIST", 1L, null);
        create(1L, "EXCLUDE_ARTIST", 1L, null);

        var overview = memory.overview(1L);
        assertEquals(1, overview.conflicts().size(), "同一歌手既喜欢又排除应记入冲突");
        assertTrue(overview.effective().likeArtists().isEmpty(), "冲突时喜欢项被移除");
        assertEquals(List.of(1L), overview.effective().excludeArtists().stream()
                .map(EffectivePreferenceItemVO::targetId).toList());

        // 检索侧与 overview 看到的是同一份合并结果。
        assertTrue(memory.exclusionConstraints(1L).excludedArtistIds().contains(1L));

        // 候选与随机推荐都不再包含歌手 A 的歌。
        assertTrue(candidateIds(1L, "测试歌手A", "", "", "20").isEmpty());
        List<Long> randomIds = randomIds(1L);
        assertFalse(randomIds.contains(10L));
        assertFalse(randomIds.contains(11L));
        assertFalse(randomIds.contains(12L));
        assertTrue(randomIds.containsAll(List.of(13L, 14L)), "随机推荐只应留下未被排除的歌");
    }

    // ================================================================ 3. 合作歌手

    @Test
    @DisplayName("合作歌曲只要有一位演唱者被排除 → 整首被排除（tb_song_artist 每人一行）")
    void cooperationSongIsExcludedWhenEitherArtistIsExcluded() throws Exception {
        insertCooperationSong();

        create(1L, "EXCLUDE_ARTIST", 2L, null);

        // 关键词精确命中合作曲，但其中一位歌手（B）被排除 → 整首不返回。
        ToolResult<List<SongRef>> byKeyword = candidates(1L, "", "合作曲", "", "20");
        assertEquals(AgentToolCodes.EMPTY, byKeyword.code());
        assertNull(byKeyword.data(), "命中排除的合作曲不得出现在结果里");

        // 按歌手 A 检索时，合作曲 20 同样被排除；A 的独唱仍在。
        assertEquals(List.of(10L, 11L, 12L), candidateIds(1L, "测试歌手A", "", "", "20"));
    }

    // ================================================================ 4. 数量 / LIMIT / 空结果

    @Test
    @DisplayName("排除发生在 LIMIT 之前：过滤后仍取满数量；全部被排除时返回明确空结果")
    void exclusionHappensBeforeLimitAndEmptyResultIsExplicit() throws Exception {
        // 歌手 A 补 25 首（100~124，风格「流行」），共 28 首，其中 12 是「摇滚」。
        insertSongsByArtist(1L, 100, 124);

        create(1L, "EXCLUDE_STYLE", null, "摇滚");

        // 若「先 LIMIT 20 再内存过滤」，10/11/12/100..116 去掉 12 只会剩 19 首；
        // 这里是 SQL 先排除再 LIMIT，因此仍应取满 20 首。
        List<Long> ids = candidateIds(1L, "测试歌手A", "", "", "20");
        assertEquals(20, ids.size(), "排除必须在 LIMIT 之前，过滤后仍应取满上限");
        assertFalse(ids.contains(12L));

        // 全部候选都被排除 → 明确的空结果，而不是让模型换个方式再捞。
        create(1L, "EXCLUDE_ARTIST", 1L, null);
        ToolResult<List<SongRef>> empty = candidates(1L, "测试歌手A", "", "", "20");
        assertEquals(AgentToolCodes.EMPTY, empty.code());
        assertNull(empty.data());
    }

    // ================================================================ 5. 多值风格 token 精确比对

    @Test
    @DisplayName("多值风格按 token 精确比对：排除「电子」命中「摇滚,电子」；排除「流行」不误命中「欧美流行」")
    void multiValueStyleIsMatchedByTokenNotSubstring() throws Exception {
        // 21：半角逗号多值；22：包含「欧美流行」的单一风格（用于验证子串不误命中）；
        // 23：全角逗号多值（验证规范化对中文逗号同样生效）。
        jdbc.update("INSERT INTO tb_song(id,name,artist_id,artist_names,style) VALUES (21,'双风格歌',2,'测试歌手B','摇滚,电子')");
        jdbc.update("INSERT INTO tb_song(id,name,artist_id,artist_names,style) VALUES (22,'欧美流行歌',2,'测试歌手B','欧美流行')");
        jdbc.update("INSERT INTO tb_song(id,name,artist_id,artist_names,style) VALUES (23,'全角逗号歌',2,'测试歌手B','电子，古风')");
        jdbc.update("INSERT INTO tb_song_artist(song_id, artist_id, display_order) VALUES (21,2,1),(22,2,1),(23,2,1)");

        // 排除「电子」→ 21（半角多值）与 23（全角多值）都被 token 命中而排除；22 与 13/14 不受影响。
        create(1L, "EXCLUDE_STYLE", null, "电子");
        List<Long> afterElectronic = candidateIds(1L, "测试歌手B", "", "", "20");
        assertFalse(afterElectronic.contains(21L), "多值风格里的「电子」应被精确命中");
        assertFalse(afterElectronic.contains(23L), "全角逗号多值风格同样应被拆分命中");
        assertTrue(afterElectronic.contains(22L), "不含「电子」token 的风格不得被误排除");
        assertTrue(afterElectronic.containsAll(List.of(13L, 14L)));

        // 再排除「流行」→ 不得子串误命中「欧美流行」，也不得命中「摇滚,电子」。
        create(1L, "EXCLUDE_STYLE", null, "流行");
        List<Long> afterPop = candidateIds(1L, "测试歌手B", "", "", "20");
        assertTrue(afterPop.contains(22L), "排除「流行」不得子串误命中「欧美流行」");
        assertFalse(afterPop.contains(21L));
        assertTrue(afterPop.containsAll(List.of(13L, 14L)));
    }

    // ================================================================ 6. 生成预览时的兜底校验

    @Test
    @DisplayName("把被排除的歌塞进 prepare → 被拒绝且不落库；修改预览塞入同样被拒")
    void prepareRejectsExcludedSongsAndLeavesNoTrace() throws Exception {
        login(1L);
        create(1L, "EXCLUDE_STYLE", null, "摇滚");

        ToolResult<PendingActionVO> rejected = prepareRaw(1L, CHAT, "含摇滚歌单", ids(10, 12), null);
        assertFalse(rejected.ok(), "草稿命中排除条件时不得生成预览");
        assertEquals(AgentToolCodes.REJECTED, rejected.code());
        // 拒绝消息必须是「下一步该做什么」的祈使句，否则模型会停下来反问用户。
        assertTrue(rejected.message().contains("searchPlaylistCandidates"), rejected.message());
        assertTrue(rejected.message().contains("不要停下来询问用户"), rejected.message());
        assertEquals(0, count("tb_agent_pending_action"), "被拒绝的请求不得留下半成品任务");
        assertEquals(0, count("tb_playlist"));

        // 不含被排除歌曲 → 正常生成。
        PendingActionVO ok = prepareViaTool(1L, CHAT, "纯流行歌单", ids(10, 11), null);
        assertEquals(2, ok.getSongCount());

        // 修改预览时把被排除的歌塞进去 → 同样被拒绝，且版本不推进。
        AgentPlaylistDraftDTO edit = new AgentPlaylistDraftDTO();
        edit.setTitle("改后含摇滚");
        edit.setSongIds(ids(10, 12));
        edit.setVersion(ok.getVersion());
        ResponseStatusException conflict = assertThrows(ResponseStatusException.class,
                () -> pendingActions.update(ok.getTaskId(), edit));
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals(1, pendingActions.get(ok.getTaskId()).getVersion(), "被拒绝的修改不得推进版本号");
    }

    // ================================================================ 7. 直接搜索不受影响

    @Test
    @DisplayName("用户当前明确发起的直接搜索不受排除影响：点名被排除的歌手/风格仍能搜到")
    void explicitDirectSearchStillFindsExcludedSongs() throws Exception {
        create(1L, "EXCLUDE_ARTIST", 1L, null);
        create(1L, "EXCLUDE_STYLE", null, "摇滚");

        MusicTools tools = readTools(new RecordingSink(), 1L, CHAT, new AgentMetrics("t", 1L, CHAT, "m"));

        assertTrue(songIdsOfJson(tools.searchSongsByArtist("测试歌手A", null)).containsAll(List.of(10L, 11L, 12L)),
                "用户明确要听某位歌手时，长期排除项不生效");
        assertTrue(songIdsOfJson(tools.getSongsByStyle("摇滚", null)).contains(12L));
        assertTrue(songIdsOfJson(tools.searchSongs("摇滚", null)).contains(12L));

        // 对照：服务端替用户挑歌的候选检索确实排除了。
        assertTrue(candidateIds(1L, "测试歌手A", "", "", "20").isEmpty());
    }

    // ================================================================ 8. 隔离性

    @Test
    @DisplayName("当前用户的排除约束不影响其他用户与游客")
    void constraintsDoNotLeakToOtherUsersOrGuests() throws Exception {
        create(1L, "EXCLUDE_ARTIST", 1L, null);

        assertTrue(candidateIds(1L, "测试歌手A", "", "", "20").isEmpty(), "用户1被排除");
        assertEquals(List.of(10L, 11L, 12L), candidateIds(2L, "测试歌手A", "", "", "20"),
                "用户2不得受用户1的偏好影响");
        assertEquals(List.of(10L, 11L, 12L), candidateIds(null, "测试歌手A", "", "", "20"),
                "游客不加任何偏好约束");

        assertTrue(randomIds(2L).containsAll(List.of(10L, 11L, 12L, 13L, 14L)),
                "用户2的随机推荐不受用户1约束影响");
    }

    // ================================================================ 9. 关闭个性化

    @Test
    @DisplayName("关闭个性化后不再使用保存的画像与手动偏好，但当前请求的显式条件仍有效")
    void personalizationOffDropsSavedExclusionsButCurrentRequestStillWorks() throws Exception {
        create(1L, "EXCLUDE_ARTIST", 1L, null);
        assertTrue(candidateIds(1L, "测试歌手A", "", "", "20").isEmpty());

        memory.setPersonalizationEnabled(1L, false);
        assertTrue(memory.exclusionConstraints(1L).isEmpty(), "关闭后约束为空");

        // 保存的排除不再生效：候选重新包含歌手 A 的歌。
        assertEquals(List.of(10L, 11L, 12L), candidateIds(1L, "测试歌手A", "", "", "20"));

        // 关闭个性化也不影响「当前请求本身」：用户这次明确要的歌手照样搜得到。
        MusicTools tools = readTools(new RecordingSink(), 1L, CHAT, new AgentMetrics("t", 1L, CHAT, "m"));
        assertTrue(songIdsOfJson(tools.searchSongsByArtist("测试歌手A", null)).containsAll(List.of(10L, 11L, 12L)));
    }

    // ================================================================ helpers

    private void create(Long userId, String category, Long targetId, String targetValue) {
        MemoryPreferenceRequest request = new MemoryPreferenceRequest();
        request.setCategory(category);
        request.setTargetId(targetId);
        request.setTargetValue(targetValue);
        memory.create(userId, request);
    }

    private ToolResult<List<SongRef>> candidates(Long userId, String artist, String keyword, String style,
                                                 String limit) throws Exception {
        RecordingSink sink = new RecordingSink();
        String raw = readTools(sink, userId, CHAT, new AgentMetrics("t", userId, CHAT, "m"))
                .searchPlaylistCandidates(artist, keyword, style, limit);
        return AgentJson.MAPPER.readValue(raw, new TypeReference<ToolResult<List<SongRef>>>() { });
    }

    private List<Long> candidateIds(Long userId, String artist, String keyword, String style, String limit)
            throws Exception {
        return songIdsOf(candidates(userId, artist, keyword, style, limit).data());
    }

    private List<Long> randomIds(Long userId) throws Exception {
        RecordingSink sink = new RecordingSink();
        String raw = readTools(sink, userId, CHAT, new AgentMetrics("t", userId, CHAT, "m")).getRandomSongs(null);
        ToolResult<List<SongRef>> result = AgentJson.MAPPER.readValue(raw,
                new TypeReference<ToolResult<List<SongRef>>>() { });
        return songIdsOf(result.data());
    }

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
                .preparePlaylistCreation(title, "偏好排除测试", "流行", csv, allowReuse);
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

    /** 双歌手合作曲：关系表两行，展示串为「A / B」。 */
    private void insertCooperationSong() {
        jdbc.update("INSERT INTO tb_song(id,name,artist_id,artist_names,style) "
                + "VALUES (20,'合作曲',1,'测试歌手A / 测试歌手B','流行')");
        jdbc.update("INSERT INTO tb_song_artist(song_id, artist_id, display_order) VALUES (20,1,1),(20,2,2)");
    }

    private void insertSongsByArtist(Long artistId, long fromId, long toId) {
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
