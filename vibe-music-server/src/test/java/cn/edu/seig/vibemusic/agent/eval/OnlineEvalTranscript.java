package cn.edu.seig.vibemusic.agent.eval;

import cn.edu.seig.vibemusic.model.agent.AgentJson;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 在线评测的<b>事实转录器</b>：把真实 {@code chat_messages} 里的工具请求/结果还原成可断言的结构。
 *
 * <p><b>为什么需要它：</b>离线评测只能从 {@code AgentEvent} 读到「调用了哪个工具」，
 * 而事件里<b>没有工具参数</b>。于是「模型把 keyword 写成什么」「songIds 是不是真实歌曲」
 * 这类问题一直无法验证 —— 恰恰是模型能力最该被评测的部分。
 * {@code MysqlChatMemoryStore.encode()} 会把完整的 {@code ToolRequestDto}（含 arguments）
 * 落进 {@code TOOL_REQUEST} 行，本类就是那条事实来源的解析器。
 *
 * <p><b>解析必须容错：</b>真实会话里可能出现半段工具交换（模型请求了工具但任务被超时打断），
 * 也可能因为历史版本差异出现无法解析的行。评测不能因为一条坏行整体崩掉，
 * 否则会把「模型答错了」误报成「评测框架坏了」—— 那会让报告失去意义。
 */
public final class OnlineEvalTranscript {

    private OnlineEvalTranscript() {}

    /**
     * 一次工具调用。
     *
     * @param id      模型给的 toolCallId，用于把请求与结果配对
     * @param tool    工具名
     * @param args    模型填写的参数（已解析）
     * @param code    结果码（{@code OK} / {@code EMPTY} / …）；半段交换或结果无法解析时为 {@code null}
     * @param kind    结果类型（{@code SONG_LIST} / {@code PLAYLIST_PREVIEW} / …）；同上可为 {@code null}
     * @param songIds 结果里出现的真实歌曲 ID（按结果顺序）；无歌曲数据时为空列表
     */
    public record ToolCall(String id, String tool, Map<String, Object> args,
                           String code, String kind, List<Long> songIds) {

        /** 原始参数值；模型可能把数字写成字符串，因此这里不做类型转换。 */
        public Object arg(String name) {
            return args.get(name);
        }

        /** 参数文本形式（去掉首尾空白）；缺失或非字符串时返回 {@code null}。 */
        public String argText(String name) {
            Object value = args.get(name);
            if (value == null) return null;
            String text = String.valueOf(value).strip();
            return text.isEmpty() ? null : text;
        }

        /**
         * 数量参数。
         *
         * <p>模型既可能传数字 {@code 5}，也可能传字符串 {@code "5"}，还可能留空 ——
         * 三种都是合法输入（工具层自己会做回落），因此这里也做同样的宽容解析，
         * 解析不出来就返回 {@code fallback}。
         */
        public Integer argInt(String name, Integer fallback) {
            Object value = args.get(name);
            if (value == null) return fallback;
            if (value instanceof Number number) return number.intValue();
            try {
                return Integer.valueOf(String.valueOf(value).strip());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        /** 是否调用了指定工具。 */
        public boolean is(String toolName) {
            return toolName.equals(tool);
        }
    }

    /**
     * 解析一段会话的工具调用。
     *
     * @param requestRows {@code TOOL_REQUEST} 行的 content（每行是一个 {@code ToolRequestDto} 数组）
     * @param resultRows  {@code TOOL_RESULT} 行的 content（每行是一个 {@code ToolResultDto}）
     * @return 按<b>请求顺序</b>排列的调用列表（不是结果到达顺序）
     */
    public static List<ToolCall> parse(List<String> requestRows, List<String> resultRows) {
        Map<String, ResultDto> results = new LinkedHashMap<>();
        for (String row : nullSafe(resultRows)) {
            ResultDto dto = readResult(row);
            if (dto != null && dto.id != null) results.put(dto.id, dto);
        }

        List<ToolCall> calls = new ArrayList<>();
        for (String row : nullSafe(requestRows)) {
            for (RequestDto request : readRequests(row)) {
                ResultDto result = request.id == null ? null : results.get(request.id);
                calls.add(new ToolCall(request.id, request.name, request.args,
                        result == null ? null : result.code,
                        result == null ? null : result.kind,
                        result == null ? List.of() : result.songIds));
            }
        }
        return List.copyOf(calls);
    }

    // ------------------------------------------------------------------ 无依据成功声明

    /**
     * 模型是否声称「歌单已创建」。
     *
     * <p>在线评测里这个信号<b>只做辅助</b>：真正的判定依据是数据库（{@code tb_playlist} 有没有新行）。
     * 但它能抓住一种数据库看不出来的错误 —— 模型说「已创建」而库里没有，说明它跳过了写工具。
     *
     * <p><b>为什么不直接复用 {@code AiController} 的护栏正则：</b>那是生产护栏，
     * 判定的是「有没有服务端事实」，包含否定与条件语境的误判风险（它有 metrics 兜底）。
     * 评测需要的是「模型说没说这句话」，因此这里的正则只要求<b>完成态</b>：
     * 有「已/成功」且没有「未/没有/无法/才会/需要你」等否定或条件词。
     */
    public static boolean claimsPlaylistCreated(String text) {
        return matches(text, CREATED_CLAIM);
    }

    /** 模型是否声称「已生成待确认预览 / 下方有确认卡片」。 */
    public static boolean claimsPreview(String text) {
        return matches(text, PREVIEW_CLAIM);
    }

    private static final Pattern CREATED_CLAIM = Pattern.compile("已(?:经)?(?:为你|帮你|替你)?(?:成功)?创建|创建成功|已成功创建");

    private static final Pattern PREVIEW_CLAIM = Pattern.compile(
            "已(?:经)?(?:为你|帮你|替你)?(?:生成|准备好|做好)(?:了)?[^。；\\n]{0,10}(?:预览|确认卡片)"
                    + "|(?:预览|确认卡片)[^。；\\n]{0,6}已(?:经)?(?:生成|准备好|做好)");

    /**
     * 否定与条件语境。命中就不算完成态声明 —— 模型说「还没有创建」「点确认后才会创建」
     * 「无法生成预览」都是在如实描述，误判成成功声明会让评测把正确行为记成错误。
     */
    private static final Pattern NEGATION = Pattern.compile(
            "未|没有|没|无法|不能|不会|尚未|无法生成|才会|才创建|需要你|需要先|如果|若|是否|吗？|\\?|？|请先|等待确认|待确认");

    private static boolean matches(String text, Pattern claim) {
        if (text == null || text.isBlank()) return false;
        if (!claim.matcher(text).find()) return false;
        return !NEGATION.matcher(text).find();
    }

    // ------------------------------------------------------------------ 行解析

    private record RequestDto(String id, String name, Map<String, Object> args) {}

    private record ResultDto(String id, String code, String kind, List<Long> songIds) {}

    private static List<RequestDto> readRequests(String row) {
        try {
            JsonNode root = AgentJson.MAPPER.readTree(row);
            if (root == null || !root.isArray()) return List.of();
            List<RequestDto> parsed = new ArrayList<>();
            for (JsonNode node : root) {
                String name = text(node, "name");
                if (name == null) continue;
                parsed.add(new RequestDto(text(node, "id"), name, readArgs(text(node, "arguments"))));
            }
            return parsed;
        } catch (Exception e) {
            // 坏行跳过：它可能是旧版本格式，也可能是半段交换。
            return List.of();
        }
    }

    private static ResultDto readResult(String row) {
        try {
            JsonNode root = AgentJson.MAPPER.readTree(row);
            if (root == null || !root.isObject()) return null;
            String id = text(root, "id");
            String payload = text(root, "text");
            if (payload == null) return new ResultDto(id, null, null, List.of());
            JsonNode result = AgentJson.MAPPER.readTree(payload);
            if (result == null || !result.isObject()) return new ResultDto(id, null, null, List.of());
            return new ResultDto(id, text(result, "code"), text(result, "kind"), songIdsOf(result.get("data")));
        } catch (Exception e) {
            return null;
        }
    }

    /** 工具参数：模型偶尔给出空串或非对象，一律按「没有参数」处理。 */
    private static Map<String, Object> readArgs(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            JsonNode node = AgentJson.MAPPER.readTree(raw);
            if (node == null || !node.isObject()) return Map.of();
            return AgentJson.MAPPER.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * 从工具结果的 {@code data} 里取歌曲 ID。
     *
     * <p>两种形状都要支持：普通检索的 {@code data} 是歌曲数组，
     * 待确认预览的 {@code data} 是一个带 {@code songs} 字段的对象。
     * 只认 {@code songId} 字段，不猜测其它命名 —— 猜错会把「模型编造的 ID」
     * 当成真实 ID，那正是评测最不能出的错。
     */
    private static List<Long> songIdsOf(JsonNode data) {
        if (data == null || data.isNull()) return List.of();
        JsonNode songs = data.isArray() ? data : data.get("songs");
        if (songs == null || !songs.isArray()) return List.of();
        List<Long> ids = new ArrayList<>();
        for (JsonNode song : songs) {
            JsonNode id = song.get("songId");
            if (id != null && id.canConvertToLong()) ids.add(id.asLong());
        }
        return List.copyOf(ids);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private static List<String> nullSafe(List<String> rows) {
        return rows == null ? List.of() : rows;
    }
}
