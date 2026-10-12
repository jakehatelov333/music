package cn.edu.seig.vibemusic.agent.eval;

import cn.edu.seig.vibemusic.agent.AbstractAgentTest;
import cn.edu.seig.vibemusic.model.entity.AiTaskMetric;
import cn.edu.seig.vibemusic.service.agent.AgentSongQueryService;
import cn.edu.seig.vibemusic.service.agent.PendingActionService;
import cn.edu.seig.vibemusic.tool.MusicTools;
import cn.edu.seig.vibemusic.tool.PlaylistActionTools;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 第 12 项验收：可重复执行的 Agent 评测入口。
 *
 * <p>运行方式：{@code mvn -o test -Dtest=AgentEvalReportTest}
 * （或 {@code mvn -o test}，它是标准测试套件的一部分）。
 *
 * <p>数据集：{@code src/test/resources/agent-eval/cases.json}，固定不变、可重复运行，
 * 不依赖开发者手工改数据库或临时调整结果。
 *
 * <p>报告输出：{@code target/agent-eval/report.md} 与 {@code report.json}。
 */
class AgentEvalReportTest extends AbstractAgentTest {

    @Autowired private MusicTools musicTools;
    @Autowired private PlaylistActionTools playlistActionTools;
    @Autowired private PendingActionService pendingActionService;
    @Autowired private AgentSongQueryService songQueryService;

    private static final String ONLINE_EVAL_COMMAND =
            "AI_EVAL_ONLINE=1 DASHSCOPE_API_KEY=<key> mvn -o test -Dtest=AgentEvalReportTest -Donline=true";

    @Test
    @DisplayName("运行 44 条离线评测用例并输出真实报告")
    void runOfflineEvaluation() throws Exception {
        List<EvalCase.Case> cases = loadCases();
        assertTrue(cases.size() >= 30 && cases.size() <= 50,
                "评测集应包含 30~50 条用例，实际 " + cases.size());

        AgentEvalHarness harness = new AgentEvalHarness(pendingActionService, actionMapper, metricMapper,
                musicTools, playlistActionTools, jdbc);

        List<EvalCase.CaseResult> results = new ArrayList<>();
        List<String> failureDetails = new ArrayList<>();

        for (EvalCase.Case testCase : cases) {
            resetData();
            ThreadLocalUtil.remove();
            long start = System.nanoTime();
            AgentEvalHarness.Outcome outcome = harness.run(testCase);
            long latencyMs = (System.nanoTime() - start) / 1_000_000L;
            String failure = AgentEvalHarness.evaluate(testCase, outcome, jdbc);
            boolean businessVerified = verifiesBusinessState(testCase);

            results.add(new EvalCase.CaseResult(testCase.id(), testCase.category(), testCase.intent(),
                    failure == null, failure, latencyMs,
                    outcome.metrics == null ? 0 : outcome.metrics.modelCalls(),
                    outcome.metrics == null ? 0 : outcome.metrics.toolCalls(),
                    outcome.metrics == null ? 0 : outcome.metrics.toolFailures(),
                    outcome.metrics == null ? null : outcome.metrics.inputTokens(),
                    outcome.metrics == null ? null : outcome.metrics.outputTokens(),
                    outcome.metrics == null ? "UNAVAILABLE" : outcome.metrics.tokenSource(),
                    businessVerified));
            if (failure != null) {
                failureDetails.add(testCase.id() + "（" + testCase.category() + "）：" + failure
                        + " | 意图：" + testCase.intent());
            }
        }

        // 指标确实落库：最近一次任务的指标行必须存在且带 taskId。
        AiTaskMetric metric = harness.latestMetric();
        assertNotNull(metric, "每次 Agent 任务都应写入 tb_ai_task_metric");
        assertNotNull(metric.getTaskId());
        assertNotNull(metric.getTokenSource());
        assertNotNull(metric.getLatencyMs());

        String markdown = renderMarkdown(cases, results, failureDetails);
        String jsonReport = renderJson(cases, results, failureDetails);
        Path dir = Path.of("target", "agent-eval");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("report.md"), markdown, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("report.json"), jsonReport, StandardCharsets.UTF_8);

        System.out.println("=== Agent 离线评测报告 ===");
        System.out.println(markdown);

        assertEquals(List.of(), failureDetails,
                "离线评测存在失败用例，详见 target/agent-eval/report.md");
    }

    private static boolean verifiesBusinessState(EvalCase.Case testCase) {
        EvalCase.Expect expect = testCase.expect();
        if (expect == null) return false;
        return Boolean.TRUE.equals(expect.playlistCreated())
                || expect.playlistSongCount() != null
                || Boolean.TRUE.equals(expect.previewMatchesPlaylist())
                || expect.pendingStatus() != null;
    }

    private List<EvalCase.Case> loadCases() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = getClass().getResourceAsStream("/agent-eval/cases.json")) {
            assertNotNull(in, "缺少评测数据集 agent-eval/cases.json");
            Map<String, Object> root = mapper.readValue(in, new com.fasterxml.jackson.core.type.TypeReference<>() { });
            Object raw = root.get("cases");
            assertTrue(raw instanceof List<?>, "cases.json 缺少 cases 数组");
            return ((List<?>) raw).stream()
                    .map(item -> mapper.convertValue(item, EvalCase.Case.class))
                    .toList();
        }
    }

    private String renderMarkdown(List<EvalCase.Case> cases, List<EvalCase.CaseResult> results,
                                  List<String> failures) {
        long passed = results.stream().filter(EvalCase.CaseResult::passed).count();
        long total = results.size();
        StringBuilder out = new StringBuilder();
        out.append("# Vibe Music Agent 离线评测报告\n\n");
        out.append("- 数据集：`src/test/resources/agent-eval/cases.json`（固定数据集，可重复运行）\n");
        out.append("- 执行时间：").append(java.time.LocalDateTime.now()).append("\n");
        out.append("- 执行方式：`mvn -o test -Dtest=AgentEvalReportTest`\n");
        out.append("- 模型：脚本化替身（离线）。工具执行、数据库写入、待确认状态机、幂等与指标落库均为真实实现。\n\n");

        out.append("## 一、总体结果\n\n");
        out.append("| 指标 | 数值 |\n|---|---|\n");
        out.append("| 用例总数 | ").append(total).append(" |\n");
        out.append("| 通过 | ").append(passed).append(" |\n");
        out.append("| 失败 | ").append(total - passed).append(" |\n");
        out.append(String.format("| 任务成功率 | %.1f%% |%n", total == 0 ? 0 : passed * 100.0 / total));
        out.append("| 其中做了数据库事实校验的用例 | ")
                .append(results.stream().filter(EvalCase.CaseResult::businessVerified).count()).append(" |\n\n");

        out.append("## 二、分类统计\n\n");
        out.append("| 类别 | 用例数 | 通过 | 成功率 |\n|---|---|---|---|\n");
        Map<String, List<EvalCase.CaseResult>> byCategory = results.stream()
                .collect(Collectors.groupingBy(EvalCase.CaseResult::category, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<String, List<EvalCase.CaseResult>> entry : byCategory.entrySet()) {
            long ok = entry.getValue().stream().filter(EvalCase.CaseResult::passed).count();
            out.append(String.format("| %s | %d | %d | %.0f%% |%n", entry.getKey(), entry.getValue().size(), ok,
                    entry.getValue().size() == 0 ? 0 : ok * 100.0 / entry.getValue().size()));
        }
        out.append('\n');

        List<Long> latencies = results.stream().map(EvalCase.CaseResult::latencyMs).sorted().toList();
        out.append("## 三、性能\n\n");
        out.append("| 指标 | 毫秒 |\n|---|---|\n");
        out.append("| P50 | ").append(percentile(latencies, 50)).append(" |\n");
        out.append("| P95 | ").append(percentile(latencies, 95)).append(" |\n");
        out.append("| 最大 | ").append(latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1)).append(" |\n");
        out.append("\n> 样本量 ").append(total).append(" 条，全部为本地内存库 + 脚本模型的执行耗时，"
                + "不含真实模型网络往返，因此不代表线上端到端延迟。\n\n");

        int toolCalls = results.stream().mapToInt(EvalCase.CaseResult::toolCalls).sum();
        int toolFailures = results.stream().mapToInt(EvalCase.CaseResult::toolFailures).sum();
        int modelCalls = results.stream().mapToInt(EvalCase.CaseResult::modelCalls).sum();
        out.append("## 四、模型与工具调用\n\n");
        out.append("| 指标 | 数值 |\n|---|---|\n");
        out.append("| 模型调用总次数 | ").append(modelCalls).append(" |\n");
        out.append("| 工具调用总次数 | ").append(toolCalls).append(" |\n");
        out.append("| 工具失败次数 | ").append(toolFailures).append(" |\n");
        out.append(String.format("| 工具调用成功率 | %.1f%% |%n",
                toolCalls == 0 ? 0 : (toolCalls - toolFailures) * 100.0 / toolCalls));
        out.append("\n> 工具失败仅统计“工具自身返回 ok=false”的次数；"
                + "其中一部分是**故意构造**的负向用例（空关键词、编造歌曲 ID、游客写操作）。\n\n");

        long providerSamples = results.stream()
                .filter(r -> "PROVIDER".equals(r.tokenSource())).count();
        long inputTokens = results.stream().filter(r -> r.inputTokens() != null)
                .mapToLong(EvalCase.CaseResult::inputTokens).sum();
        long outputTokens = results.stream().filter(r -> r.outputTokens() != null)
                .mapToLong(EvalCase.CaseResult::outputTokens).sum();
        out.append("## 五、Token 与成本\n\n");
        out.append("| 指标 | 数值 |\n|---|---|\n");
        out.append("| 拿到供应商实测用量的任务数 | ").append(providerSamples).append(" / ").append(total).append(" |\n");
        out.append("| 实测输入 Token 合计 | ").append(inputTokens).append(" |\n");
        out.append("| 实测输出 Token 合计 | ").append(outputTokens).append(" |\n");
        out.append("| 未获取用量的任务数 | ").append(total - providerSamples).append("（离线替身不返回 usage） |\n");
        out.append("\n> **成本未给出具体金额**：金额取决于当前模型（`langchain4j.dashscope.model-name`）"
                + "的最新计费单价，本报告不硬编码单价以免给出过期或错误的数字。"
                + "换算方式：`成本 = 输入 Token × 输入单价 + 输出 Token × 输出单价`，"
                + "单价请以百炼控制台当前价目为准。上线任务的真实 Token 已在 `tb_ai_task_metric` 落库，"
                + "可直接聚合出每用户/每功能成本。\n\n");

        out.append("## 六、失败用例\n\n");
        if (failures.isEmpty()) {
            out.append("无。\n\n");
        } else {
            for (String failure : failures) out.append("- ").append(failure).append('\n');
            out.append('\n');
        }

        out.append("## 七、用例明细\n\n");
        out.append("| 用例 | 类别 | 结果 | 耗时(ms) | 模型调用 | 工具调用 | 工具失败 | Token来源 | 意图 |\n");
        out.append("|---|---|---|---|---|---|---|---|---|\n");
        for (EvalCase.CaseResult result : results) {
            out.append(String.format("| %s | %s | %s | %d | %d | %d | %d | %s | %s |%n",
                    result.id(), result.category(), result.passed() ? "✅" : "❌",
                    result.latencyMs(), result.modelCalls(), result.toolCalls(), result.toolFailures(),
                    result.tokenSource(), result.intent()));
        }
        out.append('\n');

        out.append("## 八、在线端到端评测（未执行）\n\n");
        out.append("本报告不包含真实模型端到端评测：当前环境未配置 `DASHSCOPE_API_KEY`，"
                + "且执行环境无外网，无法调用 DashScope。**因此“模型是否选对工具”"
                + "“自然语言意图识别准确率”这类纯模型能力未在本报告中验证**，"
                + "标记为待测。配置好 Key 后可运行：\n\n```bash\n").append(ONLINE_EVAL_COMMAND)
                .append("\n```\n");
        out.append("\n> 对比说明：离线评测覆盖 ").append(total)
                .append(" 条用例的**确定性部分**（工具执行、结构化返回、"
                + "确认流程、幂等、越权、过期、失败分类、指标落库）；"
                + "在线评测补充**模型侧不确定性部分**（工具选择、参数正确性、澄清策略）。\n");

        return out.toString();
    }

    private String renderJson(List<EvalCase.Case> cases, List<EvalCase.CaseResult> results, List<String> failures)
            throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        long passed = results.stream().filter(EvalCase.CaseResult::passed).count();
        report.put("generatedAt", java.time.LocalDateTime.now().toString());
        report.put("datasetSize", cases.size());
        report.put("passed", passed);
        report.put("failed", results.size() - passed);
        report.put("successRate", results.isEmpty() ? 0 : (double) passed / results.size());
        report.put("onlineEval", Map.of("executed", false,
                "reason", "未配置 DASHSCOPE_API_KEY 且执行环境无外网",
                "command", ONLINE_EVAL_COMMAND));
        report.put("cases", results);
        report.put("failures", failures);
        return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report);
    }

    private static long percentile(List<Long> sorted, int percentile) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
