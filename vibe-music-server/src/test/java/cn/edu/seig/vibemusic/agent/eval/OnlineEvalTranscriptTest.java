package cn.edu.seig.vibemusic.agent.eval;

import cn.edu.seig.vibemusic.model.agent.AgentJson;
import cn.edu.seig.vibemusic.model.agent.AgentToolCodes;
import cn.edu.seig.vibemusic.model.agent.AgentToolKinds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 在线评测的转录解析器：把真实 {@code chat_messages} 里的工具请求/结果还原成可断言的结构。
 *
 * <p><b>为什么需要它：</b>离线评测只能从 {@code AgentEvent} 里读到「调用了哪个工具」，
 * 而 {@code AgentEvent} 里<b>没有工具参数</b>。于是「模型把 keyword 写成了什么」
 * 「songIds 是不是真实歌曲」这类问题一直无法验证 —— 而这恰恰是模型能力最该被评测的部分。
 * {@code MysqlChatMemoryStore.encode()} 会把完整的 {@code ToolRequestDto}（含 arguments）
 * 落进 {@code TOOL_REQUEST} 行，本类就是那条事实来源的解析器。
 *
 * <p>解析必须是<b>容错</b>的：真实会话里可能出现半段工具交换（模型请求了工具但任务被超时打断），
 * 也可能因为历史版本差异出现无法解析的行。评测不能因为一条坏行整体崩掉，
 * 否则会把「模型答错了」误报成「评测框架坏了」。
 */
class OnlineEvalTranscriptTest {

    // ------------------------------------------------------------------ 配对

    @Test
    @DisplayName("按 toolCallId 把请求与结果配对，解析出工具名、参数与结果码")
    void pairsRequestWithResultById() {
        String request = AgentJson.write(List.of(Map.of(
                "id", "call-1", "name", "searchSongs",
                "arguments", AgentJson.write(Map.of("keyword", "夜色")))));
        String result = AgentJson.write(Map.of(
                "id", "call-1", "toolName", "searchSongs",
                "text", toolResult(AgentToolCodes.OK, AgentToolKinds.SONG_LIST,
                        List.of(song(10L, "夜色温柔")), 1)));

        List<OnlineEvalTranscript.ToolCall> calls =
                OnlineEvalTranscript.parse(List.of(request), List.of(result));

        assertThat(calls).hasSize(1);
        OnlineEvalTranscript.ToolCall call = calls.get(0);
        assertThat(call.tool()).isEqualTo("searchSongs");
        assertThat(call.arg("keyword")).isEqualTo("夜色");
        assertThat(call.code()).isEqualTo(AgentToolCodes.OK);
        assertThat(call.kind()).isEqualTo(AgentToolKinds.SONG_LIST);
        assertThat(call.songIds()).containsExactly(10L);
    }

    @Test
    @DisplayName("一条 AiMessage 里的多个工具请求各自配对，顺序按请求顺序而非结果顺序")
    void pairsMultipleRequestsInOneMessage() {
        String requests = AgentJson.write(List.of(
                Map.of("id", "call-a", "name", "searchSongs", "arguments", "{\"keyword\":\"夜色\"}"),
                Map.of("id", "call-b", "name", "getSongsByStyle", "arguments", "{\"styleName\":\"摇滚\"}")));
        // 结果的落库顺序与请求顺序相反，模拟真实并发工具调用的到达顺序。
        String resultB = AgentJson.write(Map.of("id", "call-b", "toolName", "getSongsByStyle",
                "text", toolResult(AgentToolCodes.OK, AgentToolKinds.SONG_LIST, List.of(song(12L, "星河滚烫")), 1)));
        String resultA = AgentJson.write(Map.of("id", "call-a", "toolName", "searchSongs",
                "text", toolResult(AgentToolCodes.OK, AgentToolKinds.SONG_LIST, List.of(song(10L, "夜色温柔")), 1)));

        List<OnlineEvalTranscript.ToolCall> calls =
                OnlineEvalTranscript.parse(List.of(requests), List.of(resultB, resultA));

        assertThat(calls).extracting(OnlineEvalTranscript.ToolCall::tool)
                .containsExactly("searchSongs", "getSongsByStyle");
        assertThat(calls.get(0).songIds()).containsExactly(10L);
        assertThat(calls.get(1).songIds()).containsExactly(12L);
    }

    @Test
    @DisplayName("只有请求没有结果（半段交换）时保留工具名与参数，结果码为空")
    void halfExchangeKeepsRequestAndLeavesResultEmpty() {
        String request = AgentJson.write(List.of(Map.of(
                "id", "call-x", "name", "preparePlaylistCreation",
                "arguments", AgentJson.write(Map.of("songIds", "10,11")))));

        List<OnlineEvalTranscript.ToolCall> calls =
                OnlineEvalTranscript.parse(List.of(request), List.of());

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).tool()).isEqualTo("preparePlaylistCreation");
        assertThat(calls.get(0).arg("songIds")).isEqualTo("10,11");
        assertThat(calls.get(0).code()).isNull();
        assertThat(calls.get(0).songIds()).isEmpty();
    }

    // ------------------------------------------------------------------ 容错

    @Test
    @DisplayName("无法解析的行被跳过，不影响同一批里的其它工具调用")
    void malformedRowsAreSkipped() {
        String good = AgentJson.write(List.of(Map.of(
                "id", "call-1", "name", "getRandomSongs", "arguments", "{}")));
        String goodResult = AgentJson.write(Map.of("id", "call-1", "toolName", "getRandomSongs",
                "text", toolResult(AgentToolCodes.OK, AgentToolKinds.SONG_LIST, List.of(song(12L, "星河滚烫")), 1)));

        List<OnlineEvalTranscript.ToolCall> calls = OnlineEvalTranscript.parse(
                List.of("这不是 JSON", good), List.of("也不是 JSON", goodResult));

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).tool()).isEqualTo("getRandomSongs");
        assertThat(calls.get(0).songIds()).containsExactly(12L);
    }

    @Test
    @DisplayName("工具结果文本不是合法 JSON 时，保留工具名，结果码与歌曲为空")
    void unparsableToolResultKeepsTheCall() {
        String request = AgentJson.write(List.of(Map.of(
                "id", "call-1", "name", "searchSongs", "arguments", "{\"keyword\":\"夜色\"}")));
        String brokenResult = AgentJson.write(Map.of(
                "id", "call-1", "toolName", "searchSongs", "text", "not json"));

        List<OnlineEvalTranscript.ToolCall> calls =
                OnlineEvalTranscript.parse(List.of(request), List.of(brokenResult));

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).tool()).isEqualTo("searchSongs");
        assertThat(calls.get(0).code()).isNull();
        assertThat(calls.get(0).songIds()).isEmpty();
    }

    // ------------------------------------------------------------------ 歌单预览的歌曲

    @Test
    @DisplayName("待确认预览的歌曲从 data.songs 里取，而不是从 data 数组里取")
    void previewSongIdsComeFromPreviewPayload() {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("taskId", "t-1");
        preview.put("title", "夜色歌单");
        preview.put("songs", List.of(song(10L, "夜色温柔")));
        String request = AgentJson.write(List.of(Map.of(
                "id", "call-1", "name", "preparePlaylistCreation",
                "arguments", "{\"title\":\"夜色歌单\",\"songIds\":\"10\"}")));
        String result = AgentJson.write(Map.of("id", "call-1", "toolName", "preparePlaylistCreation",
                "text", toolResult(AgentToolCodes.OK, AgentToolKinds.PLAYLIST_PREVIEW, preview, 1)));

        List<OnlineEvalTranscript.ToolCall> calls =
                OnlineEvalTranscript.parse(List.of(request), List.of(result));

        assertThat(calls.get(0).songIds()).containsExactly(10L);
    }

    // ------------------------------------------------------------------ 无依据成功声明

    @Test
    @DisplayName("识别「已创建歌单」「已生成预览」这类完成态声明")
    void detectsCompletedStateClaims() {
        assertThat(OnlineEvalTranscript.claimsPlaylistCreated("已为你创建歌单《夜色》")).isTrue();
        assertThat(OnlineEvalTranscript.claimsPlaylistCreated("歌单已创建成功，共 3 首")).isTrue();
        assertThat(OnlineEvalTranscript.claimsPreview("已为你生成预览，请在下方确认卡片中确认")).isTrue();
        assertThat(OnlineEvalTranscript.claimsPreview("预览已经生成好了")).isTrue();
    }

    @Test
    @DisplayName("不把否定、条件、待确认的说法误判成成功声明")
    void doesNotFlagNegationsOrConditions() {
        assertThat(OnlineEvalTranscript.claimsPlaylistCreated("还没有创建歌单，需要你先告诉我风格")).isFalse();
        assertThat(OnlineEvalTranscript.claimsPlaylistCreated("你点击确认后才会创建歌单")).isFalse();
        assertThat(OnlineEvalTranscript.claimsPlaylistCreated("这首歌不在曲库里，所以没有生成歌单")).isFalse();
        assertThat(OnlineEvalTranscript.claimsPreview("无法生成预览，因为歌曲不存在")).isFalse();
        assertThat(OnlineEvalTranscript.claimsPreview("你是想让我先生成预览吗？")).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> song(Long id, String name) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("songId", id);
        map.put("songName", name);
        return map;
    }

    /** 与 {@code ToolResult} 的 JSON 形状保持一致：ok/code/message/kind/total/data。 */
    private static String toolResult(String code, String kind, Object data, int total) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", AgentToolCodes.OK.equals(code));
        map.put("code", code);
        map.put("message", "说明");
        map.put("kind", kind);
        map.put("total", total);
        map.put("data", data);
        return AgentJson.write(map);
    }
}
