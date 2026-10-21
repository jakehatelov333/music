package cn.edu.seig.vibemusic.agent;

import cn.edu.seig.vibemusic.model.agent.AgentEvent;
import cn.edu.seig.vibemusic.model.agent.AgentToolCodes;
import cn.edu.seig.vibemusic.model.agent.AgentToolKinds;
import cn.edu.seig.vibemusic.model.agent.SongRef;
import cn.edu.seig.vibemusic.model.agent.ToolResult;
import cn.edu.seig.vibemusic.service.agent.AgentMetrics;
import cn.edu.seig.vibemusic.tool.MusicTools;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「我最近播放了什么歌」这一问的回归保护。
 *
 * <p>背景：画像里的播放信号只有「播放过 N 首」「反复播放的歌手」，没有歌名。
 * 在 {@code getRecentPlayedSongs} 出现之前，真实端到端验证中模型会回答
 * 「系统仅记录播放行为，不提供具体歌名」—— 而数据库里明明有歌名。这不是模型幻觉，
 * 是工具集缺了这条只读路径：模型只能拿聚合数字硬答。因此这里钉住四件事：
 * <ol>
 *     <li>工具返回的是<b>真实歌名/歌手/封面/播放地址</b>，且按最近播放时间倒序；</li>
 *     <li>历史严格按当前用户隔离，模型参数里<b>没有任何用户标识</b>；</li>
 *     <li>游客得到 UNAUTHORIZED，且不会推送歌曲卡片；</li>
 *     <li>没有播放记录是 ok/EMPTY（正常结果），不是错误。</li>
 * </ol>
 */
class RecentPlayedSongsToolTest extends AbstractAgentTest {

    private final ObjectMapper json = new ObjectMapper();

    /** 直接写历史表：工具只读该表，不需要走前端上报链路。 */
    private void play(long userId, long songId, String playedAt, int count) {
        jdbc.update("INSERT INTO tb_play_history(user_id, song_id, last_played_at, play_count) "
                + "VALUES (?, ?, TIMESTAMP '" + playedAt + "', ?)", userId, songId, count);
    }

    private ToolResult<List<SongRef>> call(String limit, Long userId, RecordingSink sink) throws Exception {
        String raw = readTools(sink, userId, "c1", new AgentMetrics("t1", userId, "c1", "test-model"))
                .getRecentPlayedSongs(limit);
        return json.readValue(raw, new TypeReference<>() { });
    }

    @Test
    @DisplayName("返回真实歌名与播放地址，按最近播放时间倒序，并推送可播放卡片")
    void returnsRealSongNamesNewestFirst() throws Exception {
        play(1L, 10L, "2026-10-20 10:00:00", 2);
        play(1L, 11L, "2026-10-21 09:00:00", 1);

        RecordingSink sink = new RecordingSink();
        ToolResult<List<SongRef>> result = call(null, 1L, sink);

        assertThat(result.ok()).isTrue();
        assertThat(result.code()).isEqualTo(AgentToolCodes.OK);
        assertThat(result.kind()).isEqualTo(AgentToolKinds.SONG_LIST);
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.data()).extracting(SongRef::songName).containsExactly("晚风轻语", "夜色温柔");

        SongRef newest = result.data().get(0);
        assertThat(newest.songId()).isEqualTo(11L);
        assertThat(newest.artistName()).isEqualTo("测试歌手A");
        assertThat(newest.coverUrl()).isEqualTo("http://minio/11.jpg");
        assertThat(newest.audioUrl()).isEqualTo("http://minio/11.mp3");
        // 结构化歌手列表必须与展示串来自同一份权威关系表。
        assertThat(newest.artists()).extracting("artistName").containsExactly("测试歌手A");

        AgentEvent songs = sink.firstOfType(AgentEvent.SONGS);
        assertThat(songs).isNotNull();
        assertThat(songs.total()).isEqualTo(2);
        assertThat(sink.ofType(AgentEvent.TOOL)).hasSize(2);
    }

    @Test
    @DisplayName("只返回当前用户的历史，用户之间不串")
    void onlyReturnsOwnHistory() throws Exception {
        play(1L, 10L, "2026-10-20 10:00:00", 1);
        play(2L, 12L, "2026-10-21 10:00:00", 5);

        ToolResult<List<SongRef>> mine = call(null, 1L, new RecordingSink());
        ToolResult<List<SongRef>> other = call(null, 2L, new RecordingSink());

        assertThat(mine.data()).extracting(SongRef::songId).containsExactly(10L);
        assertThat(other.data()).extracting(SongRef::songId).containsExactly(12L);
    }

    @Test
    @DisplayName("游客得到 UNAUTHORIZED，不推送歌曲卡片")
    void guestIsRejected() throws Exception {
        play(1L, 10L, "2026-10-20 10:00:00", 1);

        RecordingSink sink = new RecordingSink();
        ToolResult<List<SongRef>> result = call(null, null, sink);

        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo(AgentToolCodes.UNAUTHORIZED);
        assertThat(sink.firstOfType(AgentEvent.SONGS)).isNull();
    }

    @Test
    @DisplayName("没有播放记录是 ok/EMPTY，不是调用失败")
    void emptyHistoryIsSuccess() throws Exception {
        RecordingSink sink = new RecordingSink();
        ToolResult<List<SongRef>> result = call(null, 1L, sink);

        assertThat(result.ok()).isTrue();
        assertThat(result.code()).isEqualTo(AgentToolCodes.EMPTY);
        assertThat(result.data()).isNull();
        assertThat(result.message()).contains("播放记录");
        assertThat(sink.firstOfType(AgentEvent.SONGS)).isNull();
    }

    @Test
    @DisplayName("limit 生效；非法或超限值回落而不是整次失败")
    void limitIsClamped() throws Exception {
        play(1L, 10L, "2026-10-20 10:00:00", 1);
        play(1L, 11L, "2026-10-21 09:00:00", 1);

        assertThat(call("1", 1L, new RecordingSink()).data()).extracting(SongRef::songId).containsExactly(11L);
        assertThat(call("abc", 1L, new RecordingSink()).total()).isEqualTo(2);
        assertThat(call("999", 1L, new RecordingSink()).total()).isEqualTo(2);
    }

    @Test
    @DisplayName("工具签名里没有用户标识参数，身份只能来自服务端上下文")
    void toolSignatureCarriesNoUserIdentity() {
        Method method = Arrays.stream(MusicTools.class.getMethods())
                .filter(m -> "getRecentPlayedSongs".equals(m.getName()))
                .findFirst()
                .orElseThrow();

        assertThat(method.getParameterTypes()).containsExactly(String.class);
        assertThat(Arrays.stream(method.getParameters()).map(p -> p.getName()).toList())
                .noneMatch(name -> name.toLowerCase().contains("user"));
    }
}
