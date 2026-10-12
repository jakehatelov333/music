package cn.edu.seig.vibemusic.agent.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * 评测数据集的模型定义。数据集存放在 {@code src/test/resources/agent-eval/cases.json}，
 * 是一份<b>固定、可重复运行</b>的清单：每条用例都有明确的意图、执行流程与判定标准。
 */
public final class EvalCase {

    private EvalCase() {}

    /** 一条评测用例。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Case(
            String id,
            String category,
            String intent,
            /** 执行流程名，见 {@code AgentEvalHarness} 的分发逻辑。 */
            String flow,
            /** 该用例是否以登录用户身份执行（模型驱动的用例需要）。 */
            Boolean login,
            Map<String, Object> args,
            Expect expect
    ) {
        public boolean authenticated() {
            return Boolean.TRUE.equals(login);
        }

        public String arg(String key) {
            Object value = args == null ? null : args.get(key);
            return value == null ? null : String.valueOf(value);
        }

        @SuppressWarnings("unchecked")
        public List<Long> longList(String key) {
            Object value = args == null ? null : args.get(key);
            if (!(value instanceof List<?> list)) return List.of();
            return list.stream().map(item -> Long.valueOf(String.valueOf(item))).toList();
        }

        @SuppressWarnings("unchecked")
        public List<Step> script() {
            Object value = args == null ? null : args.get("script");
            if (!(value instanceof List<?> list)) return List.of();
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return list.stream().map(item -> mapper.convertValue(item, Step.class)).toList();
        }

        public String chatId() {
            return "eval_" + id;
        }
    }

    /** 脚本化模型的一步。{@code tool} 非空表示请求一次工具调用，否则返回 {@code text}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(String tool, Map<String, Object> args, String text, Boolean throwError) {
        public boolean isThrow() {
            return Boolean.TRUE.equals(throwError);
        }
    }

    /** 判定标准。null 字段表示不校验该维度。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Expect(
            Boolean success,
            List<String> tools,
            List<String> eventTypes,
            Integer songCount,
            Integer modelCalls,
            Integer toolCalls,
            Integer toolFailures,
            String tokenSource,
            String failureCategory,
            String code,
            Boolean playlistCreated,
            Integer playlistSongCount,
            String pendingStatus,
            Boolean pendingExists,
            Boolean previewMatchesPlaylist,
            Integer httpStatus,
            Boolean idempotent
    ) {
    }

    /** 一条用例的执行结果。 */
    public record CaseResult(
            String id,
            String category,
            String intent,
            boolean passed,
            String failureReason,
            long latencyMs,
            int modelCalls,
            int toolCalls,
            int toolFailures,
            Long inputTokens,
            Long outputTokens,
            String tokenSource,
            boolean businessVerified
    ) {
    }
}
