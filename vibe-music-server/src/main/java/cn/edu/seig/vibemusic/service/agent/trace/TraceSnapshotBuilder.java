package cn.edu.seig.vibemusic.service.agent.trace;

import cn.edu.seig.vibemusic.model.agent.AgentJson;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一次真实模型调用 / 一次工具执行，转成可持久化的脱敏快照。
 *
 * <p><b>输入来源是唯一的：</b>消息列表就是 {@code MeteredChatModels} 拿到的那个列表，
 * 工具定义就是同一次调用传下去的那个列表。这里不做任何"按模板重建"，也不从最终回答反推，
 * 因此界面看到的就是模型当时真正看到的东西。
 *
 * <p>纯函数、无状态，便于单测直接验证脱敏与截断行为。
 */
@Component
public class TraceSnapshotBuilder {

    public static final String STATE_FULL = "FULL";
    public static final String STATE_TRUNCATED = "TRUNCATED";
    public static final String STATE_REDACTED = "REDACTED";
    public static final String STATE_DISABLED = "DISABLED";

    private final TraceRedactor redactor;
    private final AgentTraceSettings settings;

    public TraceSnapshotBuilder(TraceRedactor redactor, AgentTraceSettings settings) {
        this.redactor = redactor;
        this.settings = settings;
    }

    // ------------------------------------------------------------------ 模型调用

    /** 单次模型调用的完整快照（输入消息、系统消息、可见工具、输出、工具请求）。 */
    public LlmSnapshot llmCall(List<ChatMessage> messages, List<ToolSpecification> tools,
                               AiMessage output, String finishReason) {
        if (!settings.enabled()) {
            return new LlmSnapshot(null, null, null, null, null, null, null,
                    STATE_DISABLED, "追踪采集已关闭（ai.trace.enabled=false）");
        }
        if (!settings.captureContent()) {
            // 关闭内容采集时仍保留"调用了几次、用了哪些工具"的元数据，只是不落正文。
            String toolNames = tools == null ? null : AgentJson.write(toolNames(tools));
            return new LlmSnapshot(null, null, null, toolNames, hash(toolNames), null, null,
                    STATE_DISABLED, "内容快照已关闭（ai.trace.capture-content=false），仅保留元数据");
        }

        State state = new State();
        String systemMessage = null;
        List<Map<String, Object>> rendered = new ArrayList<>();
        for (ChatMessage message : messages == null ? List.<ChatMessage>of() : messages) {
            String text = textOf(message);
            // 系统提示词在消息列表里也用大上限：它本身就是排障核心，截断会丢失规则后半段。
            boolean system = message instanceof SystemMessage;
            if (system) systemMessage = text;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("role", roleOf(message));
            row.put("text", clip(state, text, system ? settings.maxSystemPromptChars() : settings.maxTextChars()));
            if (message instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                row.put("toolRequests", toolRequests(ai.toolExecutionRequests(), state));
            }
            if (message instanceof ToolExecutionResultMessage result) {
                row.put("toolCallId", result.id());
                row.put("toolName", result.toolName());
            }
            rendered.add(row);
        }

        String messagesJson = clipJson(state, AgentJson.write(rendered), settings.maxMessagesChars());
        // 系统提示词用单独的上限：它是排障核心证据，不能被单条消息的短上限截掉。
        String systemRedacted = systemMessage == null ? null
                : clip(state, systemMessage, settings.maxSystemPromptChars());
        String toolsJson = tools == null ? null
                : clipJson(state, AgentJson.write(toolSpecs(tools)), settings.maxToolsChars());
        String outputText = output == null || output.text() == null ? null
                : clip(state, output.text(), settings.maxTextChars());
        String toolRequestsJson = output == null || !output.hasToolExecutionRequests() ? null
                : AgentJson.write(toolRequests(output.toolExecutionRequests(), state));

        return new LlmSnapshot(messagesJson, systemRedacted, hash(systemMessage),
                toolsJson, hash(toolsJson), outputText, toolRequestsJson,
                state.value(), state.note());
    }

    // ------------------------------------------------------------------ 工具调用

    /** 工具执行快照：模型原始参数、解析后的参数、业务返回与状态。 */
    public ToolSnapshot toolCall(String rawArguments, String parsedArguments, String resultJson,
                                 String resultSummary, int resultTotal, String resultCode,
                                 boolean ok, String errorCategory) {
        if (!settings.enabled()) {
            return new ToolSnapshot(null, null, null, null, resultTotal, resultCode, errorCategory,
                    STATE_DISABLED, "追踪采集已关闭（ai.trace.enabled=false）");
        }
        if (!settings.captureContent()) {
            return new ToolSnapshot(null, null, null, resultSummary, resultTotal, resultCode, errorCategory,
                    STATE_DISABLED, "内容快照已关闭（ai.trace.capture-content=false），仅保留状态与数量");
        }
        State state = new State();
        String raw = clip(state, rawArguments, settings.maxTextChars());
        String parsed = clip(state, parsedArguments, settings.maxTextChars());
        String payload = clip(state, resultJson, settings.maxResultChars());
        String summary = clip(state, resultSummary, 500);
        return new ToolSnapshot(raw, parsed, payload, summary, resultTotal, resultCode, errorCategory,
                state.value(), state.note());
    }

    // ------------------------------------------------------------------ 通用

    /** 给前端展示的"请求摘要"：只取用户最后一条消息的前若干字，并脱敏。 */
    public String requestSummary(List<ChatMessage> messages, int maxChars) {
        if (!settings.enabled() || !settings.captureContent()) return null;
        if (messages == null) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message instanceof dev.langchain4j.data.message.UserMessage) {
                String text = textOf(message);
                if (text == null || text.isBlank()) return null;
                String clean = redactor.redact(text.replaceAll("\\s+", " ").trim()).text();
                return clean.length() <= maxChars ? clean : clean.substring(0, maxChars) + "…";
            }
        }
        return null;
    }

    public String redact(String value) {
        return redactor.redact(value).text();
    }

    public static String hash(String value) {
        if (value == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走到这里说明运行环境异常；不抛异常，避免影响业务。
            return null;
        }
    }

    // ------------------------------------------------------------------ 内部

    private List<Map<String, Object>> toolRequests(List<ToolExecutionRequest> requests, State state) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ToolExecutionRequest request : requests) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("modelToolCallId", request.id());
            row.put("name", request.name());
            row.put("arguments", clip(state, request.arguments(), settings.maxTextChars()));
            rows.add(row);
        }
        return rows;
    }

    /** 工具定义：名称、描述与参数 Schema —— 排查"工具选错"必须能对比这三项。 */
    private List<Map<String, Object>> toolSpecs(List<ToolSpecification> tools) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ToolSpecification tool : tools) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", tool.name());
            row.put("description", tool.description());
            row.put("parameters", schemaOf(tool.parameters()));
            rows.add(row);
        }
        return rows;
    }

    private static List<String> toolNames(List<ToolSpecification> tools) {
        List<String> names = new ArrayList<>();
        for (ToolSpecification tool : tools) names.add(tool.name());
        return names;
    }

    /**
     * 参数 Schema 转成普通 Map。
     *
     * <p>LangChain4j 0.36.2 的 {@code JsonSchemaElement} 没有统一的序列化入口，
     * 直接 {@code AgentJson.write} 只会得到一个没有属性的空对象 —— 那样追踪就失去意义。
     * 因此按具体类型逐层展开。
     */
    private static Object schemaOf(JsonSchemaElement element) {
        if (element == null) return null;
        if (element instanceof JsonObjectSchema object) {
            Map<String, Object> map = new LinkedHashMap<>();
            if (object.description() != null) map.put("description", object.description());
            map.put("type", "object");
            Map<String, Object> properties = new LinkedHashMap<>();
            if (object.properties() != null) {
                object.properties().forEach((name, schema) -> properties.put(name, schemaOf(schema)));
            }
            map.put("properties", properties);
            if (object.required() != null) map.put("required", object.required());
            return map;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", typeName(element));
        String description = descriptionOf(element);
        if (description != null) map.put("description", description);
        return map;
    }

    private static String typeName(JsonSchemaElement element) {
        String simple = element.getClass().getSimpleName();
        return switch (simple) {
            case "JsonStringSchema" -> "string";
            case "JsonIntegerSchema" -> "integer";
            case "JsonNumberSchema" -> "number";
            case "JsonBooleanSchema" -> "boolean";
            case "JsonArraySchema" -> "array";
            case "JsonEnumSchema" -> "enum";
            case "JsonObjectSchema" -> "object";
            default -> simple.replace("Json", "").replace("Schema", "").toLowerCase(java.util.Locale.ROOT);
        };
    }

    private static String descriptionOf(JsonSchemaElement element) {
        try {
            var method = element.getClass().getMethod("description");
            Object value = method.invoke(element);
            return value == null ? null : value.toString();
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static String roleOf(ChatMessage message) {
        return switch (message.type()) {
            case SYSTEM -> "system";
            case USER -> "user";
            case AI -> "assistant";
            case TOOL_EXECUTION_RESULT -> "tool";
            default -> message.type().name().toLowerCase(java.util.Locale.ROOT);
        };
    }

    /** 取消息的文本内容；AI 消息可能只有工具调用、没有文本。 */
    private static String textOf(ChatMessage message) {
        if (message instanceof SystemMessage system) return system.text();
        if (message instanceof dev.langchain4j.data.message.UserMessage user) return user.singleText();
        if (message instanceof AiMessage ai) return ai.text();
        if (message instanceof ToolExecutionResultMessage result) return result.text();
        return null;
    }

    /** 脱敏 + 截断，并记录事实，绝不静默。 */
    private String clip(State state, String value, int max) {
        if (value == null) return null;
        TraceRedactor.Result redacted = redactor.redact(value);
        if (redacted.redacted()) state.markRedacted();
        String text = redacted.text();
        if (text.length() <= max) return text;
        state.markTruncated(text.length(), max);
        return text.substring(0, max) + "…[已截断，原长 " + text.length() + " 字符]";
    }

    /** JSON 内容的脱敏与截断走同一条路径，只是上限不同。 */
    private String clipJson(State state, String value, int max) {
        return clip(state, value, max);
    }

    /** 累积"发生了什么"的事实，最后写进 snapshot_state / snapshot_note。 */
    private static final class State {
        private boolean redacted;
        private boolean truncated;
        private int originalLength;
        private int limit;

        void markRedacted() { this.redacted = true; }

        void markTruncated(int original, int max) {
            this.truncated = true;
            this.originalLength = Math.max(this.originalLength, original);
            this.limit = max;
        }

        String value() {
            if (truncated) return STATE_TRUNCATED;
            if (redacted) return STATE_REDACTED;
            return STATE_FULL;
        }

        String note() {
            List<String> notes = new ArrayList<>();
            if (truncated) notes.add("部分内容超过 " + limit + " 字符已截断（原长 " + originalLength + " 字符）");
            if (redacted) notes.add("命中凭据脱敏规则，敏感值已替换为 ***");
            return notes.isEmpty() ? null : String.join("；", notes);
        }
    }

    public record LlmSnapshot(String messagesJson, String systemMessage, String promptHash,
                              String toolsJson, String toolsHash, String outputText,
                              String toolRequestsJson, String state, String note) {
    }

    public record ToolSnapshot(String rawArguments, String parsedArguments, String resultPayload,
                               String resultSummary, int resultTotal, String resultCode,
                               String errorCategory, String state, String note) {
    }
}
