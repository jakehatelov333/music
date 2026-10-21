package cn.edu.seig.vibemusic.agent;

import cn.edu.seig.vibemusic.model.agent.AgentJson;
import cn.edu.seig.vibemusic.model.agent.AgentToolCodes;
import cn.edu.seig.vibemusic.model.agent.SongRef;
import cn.edu.seig.vibemusic.model.agent.ToolResult;
import cn.edu.seig.vibemusic.service.agent.AgentMetrics;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 空结果必须给「事实」，不能给「指令」。
 *
 * <p><b>真实事故（2026-10-21 21:40）：</b>用户问「推荐最近播放歌曲类似风格的歌」，
 * 模型正确地归纳出「周杰伦 + 华语流行」并调用候选检索，工具返回：
 * <pre>
 * 未找到歌手「周杰伦」、「华语流行」风格的可推荐歌曲（可能曲库中没有，或已被你设置的排除条件过滤掉）。
 * 不要改用其它工具绕过排除条件去推荐这些歌曲。
 * </pre>
 * 模型于是对用户说「很抱歉，目前曲库里暂时没有适合的推荐歌曲」。
 * 而数据库里该歌手有 7 首、该风格有 57 首 —— 只是那 7 首<b>没有风格标注</b>，交集才为 0。
 *
 * <p>根因不是模型不会推理，而是工具把「停止信号」当成了返回内容：
 * 「不要改用其它工具」直接掐断了 ReAct 循环里唯一有价值的动作 —— 换一条路再试。
 *
 * <p>本测试锁住三件事：
 * <ol>
 *     <li>空结果的 message 里<b>不再</b>出现禁止探索的指令；</li>
 *     <li>必须给出「完整条件 / 放宽某一项」的真实数量，让模型能判断是哪一项卡住了；</li>
 *     <li>诊断数字必须与数据库一致（这是模型做决策的唯一依据，错了会误导决策）。</li>
 * </ol>
 */
class EmptyResultDiagnosisTest extends AbstractAgentTest {

    private static final String CHAT = "chat-empty-diagnosis";

    @Test
    @DisplayName("歌手与风格交集为 0 时，返回逐项放宽后的真实数量，而不是「曲库没有」")
    void emptyResultReportsRelaxedCounts() throws Exception {
        // 夹具：测试歌手A 有 3 首（风格为「流行」「流行」「摇滚」）。
        // 用「摇滚」做交集能命中；用「粤语」则交集为 0，但歌手本身有 3 首。
        ToolResult<List<SongRef>> empty = candidates(1L, CHAT, "测试歌手A", "", "粤语", "10");

        assertEquals(AgentToolCodes.EMPTY, empty.code(), "没有命中是 EMPTY，不是失败");
        String message = empty.message();

        // ① 不许再出现「停止信号」。
        assertFalse(message.contains("不要改用其它工具"),
                "空结果不得禁止模型换路径探索，这正是把它变成固定流程的原因：" + message);

        // ② 必须给出「完整条件命中多少」。
        assertTrue(message.contains("完整条件在曲库中命中 0 首"),
                "要说明完整条件的命中数，模型才能判断是曲库没有还是条件太窄：" + message);

        // ③ 必须逐项给出放宽后的数量，指出是哪一项卡住了。
        assertTrue(message.contains("只按歌手「测试歌手A」有 3 首"),
                "应给出只按歌手的数量：" + message);
        assertTrue(message.contains("只按风格「粤语」有 1 首"),
                "应给出只按风格的数量（夹具里只有 id=14 一首粤语歌）：" + message);
    }

    @Test
    @DisplayName("复现真实事故：歌手有歌但全部缺风格标注时，诊断能指出是交集过窄而非曲库没有")
    void songsWithoutStyleTagsStillReportArtistCount() throws Exception {
        // 完全复刻「周杰伦 7 首 style 全空」的形态：给测试歌手B 插入 5 首无风格标注的歌。
        insertSongsWithoutStyle(2L, 200, 204);

        ToolResult<List<SongRef>> empty = candidates(1L, CHAT, "测试歌手B", "", "华语流行", "10");

        assertEquals(AgentToolCodes.EMPTY, empty.code(), empty.message());
        String message = empty.message();

        assertFalse(message.contains("不要改用其它工具"), "不得禁止换路径：" + message);
        // 夹具里测试歌手B 原有 2 首（民谣/粤语）+ 新增 5 首 = 7 首。
        assertTrue(message.contains("只按歌手「测试歌手B」有 7 首"),
                "即使风格标注缺失，也必须如实报告该歌手的歌曲总数：" + message);
        assertTrue(message.contains("只按风格「华语流行」有"),
                "同时给出该风格在曲库中的数量，模型才能判断两条路都可行：" + message);
        assertTrue(message.contains("交集过窄"),
                "应明确指出是条件交集的问题，而不是笼统说「可能曲库中没有」：" + message);
    }

    @Test
    @DisplayName("诊断只数客观数量：不应用会话历史与用户偏好排除")
    void diagnosisCountsIgnoreHistoryAndPreferenceExclusions() throws Exception {
        // 历史排除的数据来源是「已生成的待确认预览」，不是一次普通检索：
        // 必须真的走 preparePlaylistCreation 才会在 tb_agent_pending_action 留下记录。
        ToolResult<List<SongRef>> first = candidates(1L, CHAT, "测试歌手A", "", "", "10");
        assertEquals(AgentToolCodes.OK, first.code(), first.message());
        prepareViaTool(1L, CHAT, "诊断用歌单", songIdsOf(first.data()));

        // 这些歌已被本会话用过，再查同一条件 → 候选为空。
        ToolResult<List<SongRef>> again = candidates(1L, CHAT, "测试歌手A", "", "", "10");
        assertEquals(AgentToolCodes.EMPTY, again.code(), again.message());

        String message = again.message();
        assertTrue(message.contains("本会话近期歌单已用过"),
                "必须告诉模型排除项生效了，否则它会误以为曲库没有：" + message);
        assertTrue(message.contains("完整条件在曲库中命中 3 首"),
                "诊断数量是客观口径，不能因为会话历史而变成 0：" + message);
        // 会话历史这一路：告诉模型「用过的不再重复」，并给出复用这条出路，而不是让它说「没有」。
        assertTrue(message.contains("不会重复出现"), "应说明历史排除的作用：" + message);
        assertTrue(message.contains("复用"), "应给出复用这条出路：" + message);
        // 注意：本例没有「用户偏好排除」，因此不该出现偏好约束那句 —— 两种排除要分得清。
        assertFalse(message.contains("用户偏好排除生效"),
                "没有偏好排除时不应声称有：" + message);
    }

    @Test
    @DisplayName("countMatching 与候选检索使用同一套筛选条件，数量不会自相矛盾")
    void countMatchingAgreesWithCandidateSearch() throws Exception {
        // 单条件：风格「流行」在夹具里有 2 首（10、11）。
        assertEquals(2L, songQuery.countMatching(null, null, "流行"));
        // 单条件：歌手「测试歌手A」有 3 首。
        assertEquals(3L, songQuery.countMatching(null, "测试歌手A", null));
        // 交集：歌手A + 流行 = 2 首。
        assertEquals(2L, songQuery.countMatching(null, "测试歌手A", "流行"));
        // 交集为空。
        assertEquals(0L, songQuery.countMatching(null, "测试歌手A", "粤语"));
        // 解析不到的歌手名 → 0，而不是「不加条件」。
        assertEquals(0L, songQuery.countMatching(null, "不存在的歌手", null));
        // 什么条件都没有 → 0（调用方本就不该这么问，这里只保证不会退化成全表计数）。
        assertEquals(0L, songQuery.countMatching(null, null, null));
    }

    // ------------------------------------------------------------------ 辅助

    private ToolResult<List<SongRef>> candidates(Long userId, String chatId, String artist, String keyword,
                                                 String style, String limit) throws Exception {
        RecordingSink sink = new RecordingSink();
        String raw = readTools(sink, userId, chatId, new AgentMetrics("t", userId, chatId, "m"))
                .searchPlaylistCandidates(artist, keyword, style, limit);
        return AgentJson.MAPPER.readValue(raw, new TypeReference<ToolResult<List<SongRef>>>() { });
    }

    /** 走真实的写工具生成待确认预览：会话选曲历史只认 tb_agent_pending_action 里的记录。 */
    private void prepareViaTool(Long userId, String chatId, String title, List<Long> songIds) throws Exception {
        RecordingSink sink = new RecordingSink();
        String csv = songIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        String raw = actionTools(sink, userId, chatId, new AgentMetrics("t", userId, chatId, "m"))
                .preparePlaylistCreation(title, "诊断测试", "流行", csv, null);
        ToolResult<?> result = AgentJson.MAPPER.readValue(raw, ToolResult.class);
        assertTrue(result.ok(), "预览应生成成功：" + result.code() + " / " + result.message());
    }

    private static List<Long> songIdsOf(List<SongRef> songs) {
        if (songs == null) return List.of();
        return songs.stream().map(SongRef::songId).toList();
    }

    /** 插入风格为空的歌曲：复刻「专辑上传路径没有 style 字段」造成的真实数据形态。 */
    private void insertSongsWithoutStyle(Long artistId, long fromId, long toId) {
        String artistName = artistId == 2L ? "测试歌手B" : "测试歌手A";
        List<Object[]> songs = new ArrayList<>();
        List<Object[]> relations = new ArrayList<>();
        for (long id = fromId; id <= toId; id++) {
            songs.add(new Object[]{id, "无风格歌曲" + id, artistId, artistName});
            relations.add(new Object[]{id, artistId});
        }
        jdbc.batchUpdate("INSERT INTO tb_song(id,name,artist_id,artist_names,style) VALUES (?,?,?,?,NULL)", songs);
        jdbc.batchUpdate("INSERT INTO tb_song_artist(song_id, artist_id, display_order) VALUES (?,?,1)", relations);
    }
}
