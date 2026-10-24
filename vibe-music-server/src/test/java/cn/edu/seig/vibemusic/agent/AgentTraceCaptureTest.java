package cn.edu.seig.vibemusic.agent;

import cn.edu.seig.vibemusic.agent.eval.AgentEvalHarness;
import cn.edu.seig.vibemusic.agent.eval.EvalCase;
import cn.edu.seig.vibemusic.mapper.AiTraceMapper;
import cn.edu.seig.vibemusic.mapper.AiTraceSearchMapper;
import cn.edu.seig.vibemusic.memory.MysqlChatMemoryStore;
import cn.edu.seig.vibemusic.model.agent.AgentEventSink;
import cn.edu.seig.vibemusic.model.agent.AgentJson;
import cn.edu.seig.vibemusic.model.entity.AiTraceLlmCall;
import cn.edu.seig.vibemusic.model.entity.AiTraceSpan;
import cn.edu.seig.vibemusic.model.entity.AiTraceToolCall;
import cn.edu.seig.vibemusic.model.vo.AiCostVO;
import cn.edu.seig.vibemusic.service.agent.trace.*;
import cn.edu.seig.vibemusic.service.ai.AiAssistantFactory;
import cn.edu.seig.vibemusic.service.ai.StaticSystemPrompt;
import cn.edu.seig.vibemusic.tool.MusicTools;
import cn.edu.seig.vibemusic.tool.PlaylistActionTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.dashscope.QwenChatModel;
import dev.langchain4j.model.dashscope.QwenStreamingChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Trace 采集的端到端验证：真实 AiServices 编排 + 真实工具执行 + 真实数据库，
 * 只有模型是脚本替身。
 *
 * <p>要验证的是"落库的内容是不是真的"：
 * <ul>
 *     <li>每次模型调用有独立明细，Token 分输入/输出；</li>
 *     <li>模型侧 Tool Call ID 与服务端执行 ID 的关联是构造出来的，不是时间推断；</li>
 *     <li>系统提示词快照就是真正发给模型的那一份；</li>
 *     <li>工具参数/返回真实可查，空结果与失败可区分；</li>
 *     <li>父子关系是真实因果（TOOL 挂在发起它的 MODEL 下）。</li>
 * </ul>
 */
class AgentTraceCaptureTest extends AbstractAgentTest {

    @Autowired private AiTraceMapper traceMapper;
    @Autowired private AiTraceSearchMapper searchMapper;
    @Autowired private MusicTools musicTools;
    @Autowired private PlaylistActionTools playlistActionTools;

    private AgentTraceRecorder recorder;

    @BeforeEach
    void setUpRecorder() {
        TraceRedactor redactor = new TraceRedactor();
        AgentTraceSettings settings = new AgentTraceSettings();
        ReflectionTestUtils.setField(settings, "enabled", true);
        ReflectionTestUtils.setField(settings, "captureContent", true);
        ReflectionTestUtils.setField(settings, "maxTextChars", 4000);
        ReflectionTestUtils.setField(settings, "maxSystemPromptChars", 40000);
        ReflectionTestUtils.setField(settings, "maxToolsChars", 60000);
        ReflectionTestUtils.setField(settings, "maxMessagesChars", 60000);
        ReflectionTestUtils.setField(settings, "maxResultChars", 20000);
        ReflectionTestUtils.setField(settings, "retentionDays", 30);
        recorder = new AgentTraceRecorder(traceMapper, new TraceSnapshotBuilder(redactor, settings), settings);
    }

    // ------------------------------------------------------------------ 夹具

    private static EvalCase.Step tool(String name, Map<String, Object> args) {
        return new EvalCase.Step(name, args, null, false);
    }

    private static EvalCase.Step text(String value) {
        return new EvalCase.Step(null, null, value, false);
    }

    private AiAssistantFactory factory(List<EvalCase.Step> script) {
        QwenChatModel model = AgentEvalHarness.scriptedModel(script, false);
        QwenStreamingChatModel streaming = AgentEvalHarness.scriptedStreaming(script, false);
        AiAssistantFactory factory = new AiAssistantFactory(model, streaming,
                new MysqlChatMemoryStore(AgentEvalHarness.fakeAiService(), new ObjectMapper()),
                musicTools, playlistActionTools, new StaticSystemPrompt(), recorder);
        ReflectionTestUtils.setField(factory, "timeoutSeconds", 15);
        ReflectionTestUtils.setField(factory, "maxToolCalls", 4);
        ReflectionTestUtils.setField(factory, "modelName", "qwen-flash-trace");
        return factory;
    }

    /** 跑一次真实的 Agent 调用并结算（等价于控制器里的"调用 + 落库"）。 */
    private String run(List<EvalCase.Step> script, String chatId, Long userId, String query) {
        AiAssistantFactory factory = factory(script);
        try (AiAssistantFactory.Run run = factory.create(chatId, userId, AgentEventSink.NOOP)) {
            String text = run.assistant().chat(chatId, query);
            recorder.userQuery(run.trace(), query);
            run.metrics().finish(true, "NONE", null);
            recorder.guardApplied(run.trace(), false, text, null);
            recorder.finish(run.trace(), true, "NONE", null, run.metrics().latencyMillis());
            recorder.flush(run.trace());
            return run.taskId();
        }
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("一次模型调用产生独立明细：输入消息、系统提示词、工具定义、输出、Token 分项")
    void singleModelCallIsCapturedInFull() {
        String traceId = run(List.of(text("你好，想听点什么？")), "trace_chat_1", 1L, "推荐点歌");

        List<AiTraceSpan> spans = traceMapper.findSpans(traceId);
        List<AiTraceSpan> modelSpans = spans.stream()
                .filter(s -> AiTraceSpan.TYPE_MODEL.equals(s.getSpanType())).toList();
        assertEquals(1, modelSpans.size(), "应恰好有一次模型调用");
        AiTraceSpan modelSpan = modelSpans.get(0);
        assertEquals("qwen-flash-trace", modelSpan.getModel());
        assertEquals(80L, modelSpan.getInputTokens(), "输入 Token 应取自供应商返回值");
        assertEquals(20L, modelSpan.getOutputTokens(), "输出 Token 应单独记录");
        assertEquals("PROVIDER", modelSpan.getTokenSource());

        AiTraceLlmCall call = traceMapper.findLlmCallBySpan(modelSpan.getSpanId());
        assertNotNull(call, "每次模型调用必须有独立明细");
        assertEquals(1, call.getCallIndex());
        assertNotNull(call.getMessagesJson(), "必须记录真实输入消息");
        assertTrue(call.getMessagesJson().contains("推荐点歌"), "输入里应包含用户本轮问题");
        assertNotNull(call.getSystemMessage(), "必须记录实际生效的 System Message");
        assertTrue(call.getSystemMessage().contains("Vibe Music"), "系统提示词应是真实内容而非空模板");
        assertNotNull(call.getToolsJson(), "必须记录模型可见的工具定义");
        assertTrue(call.getToolsJson().contains("searchSongs"), "工具定义应含真实工具名");
        assertTrue(call.getToolsJson().contains("parameters"), "工具定义应含参数 Schema");
        assertEquals("你好，想听点什么？", call.getOutputText());
        assertEquals("FULL", call.getSnapshotState());
        assertNull(call.getSnapshotNote());
    }

    @Test
    @DisplayName("多轮工具调用：每次模型调用各自一行，Token 可累加核对，工具挂在发起它的模型轮次下")
    void multiRoundCallsAreSeparatedAndLinkedToParent() {
        String traceId = run(List.of(
                tool("searchSongs", Map.of("keyword", "夜色")),
                text("为你找到《夜色温柔》")), "trace_chat_2", 1L, "搜一下夜色");

        List<AiTraceSpan> spans = traceMapper.findSpans(traceId);
        List<AiTraceSpan> modelSpans = spans.stream()
                .filter(s -> AiTraceSpan.TYPE_MODEL.equals(s.getSpanType())).toList();
        assertEquals(2, modelSpans.size(), "工具轮 + 收尾轮 = 两次模型调用");

        List<AiTraceLlmCall> calls = traceMapper.findLlmCalls(traceId);
        assertEquals(2, calls.size());
        assertEquals(1, calls.get(0).getCallIndex());
        assertEquals(2, calls.get(1).getCallIndex());
        // 第 2 轮的输入必须比第 1 轮多：模型自己的工具请求 + 工具返回结果。
        assertTrue(calls.get(1).getMessagesJson().length() > calls.get(0).getMessagesJson().length(),
                "第 2 轮输入应包含工具请求与工具返回");
        assertTrue(calls.get(1).getMessagesJson().contains("tool"), "第 2 轮应含工具结果消息");

        long sum = modelSpans.stream().mapToLong(AiTraceSpan::getInputTokens).sum();
        assertEquals(200L, sum, "两次调用输入 Token 之和（120 + 80）");

        // 工具 Span 的父节点必须是发起它的那一轮模型调用。
        AiTraceSpan toolSpan = spans.stream()
                .filter(s -> AiTraceSpan.TYPE_TOOL.equals(s.getSpanType())).findFirst().orElseThrow();
        assertEquals(modelSpans.get(0).getSpanId(), toolSpan.getParentSpanId(),
                "工具应挂在第 1 轮模型调用下，而不是按时间顺序猜");
        assertEquals(AiTraceSpan.STATUS_OK, toolSpan.getStatus());
    }

    @Test
    @DisplayName("模型原始 Tool Call 与服务端执行可靠关联：两个 ID 都留存，且参数与返回可查")
    void toolCallLinksModelRequestToServerExecution() {
        String traceId = run(List.of(
                tool("searchSongs", Map.of("keyword", "夜色")),
                text("找到了")), "trace_chat_3", 1L, "搜一下夜色");

        List<AiTraceToolCall> tools = traceMapper.findToolCalls(traceId);
        assertEquals(1, tools.size());
        AiTraceToolCall call = tools.get(0);
        assertEquals("searchSongs", call.getToolName());
        // 模型侧 ID 来自脚本替身构造的 ToolExecutionRequest。
        assertEquals("call-0", call.getModelToolCallId());
        assertNotNull(call.getServerToolCallId(), "服务端执行 ID 必须存在");
        assertEquals(call.getSpanId(), call.getServerToolCallId(), "服务端执行 ID 与 Span ID 应一致");
        // 关键：两者不是同一个来源，但被可靠地关联到同一行。
        assertNotEquals(call.getModelToolCallId(), call.getServerToolCallId(),
                "模型侧 ID 与服务端 ID 是独立生成的，不能假定相等");
        assertTrue(call.getRawArguments().contains("夜色"), "必须保留模型生成的原始参数");
        assertNotNull(call.getParsedArguments(), "必须保留服务端解析后的参数");
        assertEquals("OK", call.getResultCode());
        assertEquals(1, call.getResultTotal(), "夜色关键词命中 1 首");
        assertNotNull(call.getResultPayload());
        assertTrue(call.getResultPayload().contains("夜色温柔"), "必须保留真实返回数据");
    }

    @Test
    @DisplayName("工具空结果与工具失败在追踪里可区分")
    void emptyAndFailedToolsAreDistinguishable() {
        String emptyTrace = run(List.of(
                tool("searchSongs", Map.of("keyword", "不存在的歌手zzz")),
                text("没找到")), "trace_chat_empty", 1L, "搜一下");
        AiTraceToolCall empty = traceMapper.findToolCalls(emptyTrace).get(0);
        assertEquals("EMPTY", empty.getResultCode());
        assertEquals("EMPTY", empty.getStatus(), "空结果是成功语义，不是失败");

        String invalidTrace = run(List.of(
                tool("searchSongsByArtist", Map.of()),
                text("请提供歌手名")), "trace_chat_invalid", 1L, "搜一下");
        AiTraceToolCall invalid = traceMapper.findToolCalls(invalidTrace).get(0);
        assertEquals("INVALID_ARGUMENT", invalid.getResultCode());
        assertEquals("REJECTED", invalid.getStatus(), "参数非法属于可纠正的拒绝，不是系统错误");
    }

    @Test
    @DisplayName("系统提示词版本入库：哈希、版本号与原文都可还原")
    void promptVersionIsRegisteredAndRestorable() {
        String traceId = run(List.of(text("好的")), "trace_chat_prompt", 1L, "你好");

        AiTraceSpan context = traceMapper.findSpans(traceId).stream()
                .filter(s -> AiTraceSpan.TYPE_CONTEXT.equals(s.getSpanType())).findFirst().orElseThrow();
        Map<?, ?> attributes = parse(context.getAttributesJson());
        String hash = (String) attributes.get("basePromptHash");
        assertNotNull(hash, "必须记录基础提示词哈希");
        assertEquals(64, hash.length(), "应为 SHA-256 十六进制串");
        assertNotNull(attributes.get("effectivePrompt"), "必须保存实际生效的完整提示词");
        assertNotNull(attributes.get("blocks"), "必须记录动态块注入状态");

        String base = traceMapper.findBasePrompt(hash);
        assertNotNull(base, "只存哈希不够，必须能还原原文");
        assertTrue(base.contains("Vibe Music"));
        assertEquals(hash.substring(0, 12), traceMapper.findPromptVersion(hash));
        // 同一版本重复出现时只保留一份，计数递增（不是插入第二行）。
        run(List.of(text("好的")), "trace_chat_prompt2", 1L, "再来");
        assertEquals(1, count("tb_ai_trace_prompt"), "同一提示词版本不应重复插入");
        Integer seen = jdbc.queryForObject(
                "SELECT seen_count FROM tb_ai_trace_prompt WHERE prompt_hash = ?", Integer.class, hash);
        assertEquals(2, seen, "重复出现应累加计数，证明按哈希去重生效");
    }

    @Test
    @DisplayName("执行树包含请求、上下文、模型、工具、护栏、最终输出六类节点")
    void executionTreeContainsAllStages() {
        String traceId = run(List.of(
                tool("searchSongs", Map.of("keyword", "夜色")),
                text("找到了")), "trace_chat_tree", 1L, "搜一下夜色");
        List<String> types = traceMapper.findSpans(traceId).stream().map(AiTraceSpan::getSpanType).toList();
        assertTrue(types.contains(AiTraceSpan.TYPE_REQUEST), "缺请求初始化节点");
        assertTrue(types.contains(AiTraceSpan.TYPE_CONTEXT), "缺上下文构建节点");
        assertTrue(types.contains(AiTraceSpan.TYPE_MODEL), "缺模型调用节点");
        assertTrue(types.contains(AiTraceSpan.TYPE_TOOL), "缺工具执行节点");
        assertTrue(types.contains(AiTraceSpan.TYPE_GUARD), "缺护栏节点");
        assertTrue(types.contains(AiTraceSpan.TYPE_FINAL), "缺最终输出节点");

        // 根节点必须是 REQUEST 且没有父节点。
        AiTraceSpan root = traceMapper.findSpans(traceId).stream()
                .filter(s -> AiTraceSpan.TYPE_REQUEST.equals(s.getSpanType())).findFirst().orElseThrow();
        assertNull(root.getParentSpanId());
        assertEquals(1L, root.getUserId());
        assertEquals("trace_chat_tree", root.getChatId());
        assertNotNull(root.getRequestSummary(), "根节点应带脱敏请求摘要");
    }

    @Test
    @DisplayName("采集失败不影响业务：追踪写入异常时用户仍然拿到回复")
    void traceFailureDoesNotBreakTheReply() {
        AiTraceMapper broken = org.mockito.Mockito.mock(AiTraceMapper.class);
        org.mockito.Mockito.when(broken.insertSpan(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("db down"));
        TraceRedactor redactor = new TraceRedactor();
        AgentTraceSettings settings = new AgentTraceSettings();
        ReflectionTestUtils.setField(settings, "enabled", true);
        ReflectionTestUtils.setField(settings, "captureContent", true);
        AgentTraceRecorder failing = new AgentTraceRecorder(broken,
                new TraceSnapshotBuilder(redactor, settings), settings);

        AiAssistantFactory factory = new AiAssistantFactory(
                AgentEvalHarness.scriptedModel(List.of(text("正常回复")), false),
                AgentEvalHarness.scriptedStreaming(List.of(text("正常回复")), false),
                new MysqlChatMemoryStore(AgentEvalHarness.fakeAiService(), new ObjectMapper()),
                musicTools, playlistActionTools, new StaticSystemPrompt(), failing);
        ReflectionTestUtils.setField(factory, "timeoutSeconds", 15);
        ReflectionTestUtils.setField(factory, "maxToolCalls", 4);
        ReflectionTestUtils.setField(factory, "modelName", "qwen-flash-trace");

        try (AiAssistantFactory.Run run = factory.create("trace_chat_broken", 1L, AgentEventSink.NOOP)) {
            String text = assertDoesNotThrow(() -> run.assistant().chat("trace_chat_broken", "你好"),
                    "追踪写入失败不得让业务抛错");
            assertEquals("正常回复", text);
            failing.finish(run.trace(), true, "NONE", null, 1L);
            assertDoesNotThrow(() -> failing.flush(run.trace()), "落库失败必须被吞掉");
        }
        assertTrue(failing.failures() > 0, "失败必须被计数，不能静默掩盖");
        assertEquals(0, count("tb_ai_trace_span"), "本次没有任何 Span 落库");
    }

    @Test
    @DisplayName("关闭内容采集时只留元数据，并明确标注 DISABLED，不伪装成完整原文")
    void contentCaptureDisabledIsLabelled() {
        TraceRedactor redactor = new TraceRedactor();
        AgentTraceSettings settings = new AgentTraceSettings();
        ReflectionTestUtils.setField(settings, "enabled", true);
        ReflectionTestUtils.setField(settings, "captureContent", false);
        TraceSnapshotBuilder builder = new TraceSnapshotBuilder(redactor, settings);

        TraceSnapshotBuilder.LlmSnapshot snapshot = builder.llmCall(
                List.of(dev.langchain4j.data.message.UserMessage.from("秘密对话")), List.of(),
                dev.langchain4j.data.message.AiMessage.from("回复"), null);
        assertNull(snapshot.messagesJson(), "关闭采集时不得保存消息正文");
        assertNull(snapshot.outputText());
        assertEquals("DISABLED", snapshot.state());
        assertNotNull(snapshot.note(), "必须说明为什么没有内容");
        assertTrue(snapshot.note().contains("关闭"));
    }

    @Test
    @DisplayName("检索与聚合 SQL 在真实库上可用：按用户/状态/工具筛选、分页、总览")
    void queriesWorkAgainstRealSql() {
        String traceId = run(List.of(
                tool("searchSongs", Map.of("keyword", "夜色")),
                text("找到了")), "trace_chat_query", 1L, "搜一下夜色");
        // 任务级汇总表由控制器写入；这里手工补一条，模拟真实链路。
        jdbc.update("INSERT INTO tb_ai_task_metric(task_id, user_id, chat_id, model, model_calls, tool_calls, "
                        + "tool_failures, input_tokens, output_tokens, token_source, result_count, success, "
                        + "failure_category, output_guarded, latency_ms, created_at) "
                        + "VALUES (?, 1, 'trace_chat_query', 'qwen-flash-trace', 2, 1, 0, 200, 50, 'PROVIDER', 1, TRUE, "
                        + "'NONE', FALSE, 1234, CURRENT_TIMESTAMP)",
                traceId);

        cn.edu.seig.vibemusic.model.dto.AiTraceQueryDTO query =
                new cn.edu.seig.vibemusic.model.dto.AiTraceQueryDTO();
        // 显式给区间：默认区间是「到此刻为止」且上界开区间，
        // 刚写进去的记录有极小概率与 now() 落在同一时钟刻度而被排除（测试会偶发失败）。
        query.setFrom(java.time.LocalDateTime.now().minusDays(1));
        query.setTo(java.time.LocalDateTime.now().plusDays(1));
        query.setUserId(1L);
        query.setStatus("SUCCESS");
        query.setToolName("searchSongs");
        query.setPageNum(1);
        query.setPageSize(10);

        cn.edu.seig.vibemusic.service.agent.trace.AiCostCalculator costs =
                new cn.edu.seig.vibemusic.service.agent.trace.AiCostCalculator();
        AgentTraceSettings settings = new AgentTraceSettings();
        ReflectionTestUtils.setField(settings, "enabled", true);
        ReflectionTestUtils.setField(settings, "captureContent", true);
        ReflectionTestUtils.setField(settings, "retentionDays", 30);
        cn.edu.seig.vibemusic.service.agent.trace.AiTraceQueryService service =
                new cn.edu.seig.vibemusic.service.agent.trace.AiTraceQueryService(
                        traceMapper, searchMapper, metricMapper, costs, settings, recorder);

        var page = service.search(query);
        assertEquals(1, page.getTotal());
        assertEquals(traceId, page.getItems().get(0).getTraceId());
        assertEquals("SUCCESS", page.getItems().get(0).getStatus());
        assertEquals("alice", page.getItems().get(0).getUsername());
        assertTrue(page.getItems().get(0).getTraced(), "应标记已有 Trace 明细");
        assertEquals("搜一下夜色", page.getItems().get(0).getRequestSummary());

        var detail = service.detail(traceId);
        assertEquals(traceId, detail.getTraceId());
        assertEquals(2, detail.getLlmCalls().size());
        assertEquals(1, detail.getToolCallsDetail().size());
        assertEquals("searchSongs", detail.getToolCallsDetail().get(0).getToolName());
        assertFalse(detail.getSpans().isEmpty());
        Map<?, ?> consistency = (Map<?, ?>) detail.getConsistency();
        assertEquals(Boolean.TRUE, consistency.get("comparable"));
        // 指标表里手工写的是 200/50，Span 明细是 120+80=200 / 30+20=50 —— 必须一致。
        assertEquals(Boolean.TRUE, consistency.get("consistent"));
        assertFalse(detail.getCost().isConfigured(), "未配置单价时必须明确标记未配置");

        // 上下文快照必须来自 CONTEXT Span（根 Span 只有摘要），否则前端「上下文」页会是空的。
        Map<?, ?> context = (Map<?, ?>) detail.getContext();
        assertNotNull(context, "详情必须带上下文快照");
        assertNotNull(context.get("basePromptHash"), "上下文快照应含提示词哈希");
        assertNotNull(context.get("effectivePrompt"), "上下文快照应含实际生效的提示词");
        assertNotNull(context.get("blocks"), "上下文快照应含动态块注入状态");
        // 每次模型调用也要能单独看到"当时到底给了模型什么"。
        assertNotNull(detail.getLlmCalls().get(0).getEffectivePrompt());
        assertNotNull(detail.getLlmCalls().get(0).getPromptVersion());

        var overview = service.overview(
                java.time.LocalDateTime.now().minusDays(1),
                java.time.LocalDateTime.now().plusDays(1));
        assertEquals(1, overview.getTotalRequests());
        assertEquals(200L, overview.getInputTokens());
        assertEquals(250L, overview.getTotalTokens());
        assertEquals(1, overview.getToolCalls());
        assertNotNull(overview.getTrend());
        assertEquals("统计范围：Agent 主对话任务（tb_ai_task_metric）。"
                + "不含会话摘要、画像概述等后台模型调用，因此不等同于全站所有 AI 功能的总用量。",
                overview.getScope());
    }

    @Test
    @DisplayName("按账号筛选：账号不存在时返回空页，而不是退化成不过滤")
    void unknownUsernameReturnsEmptyPage() {
        cn.edu.seig.vibemusic.service.agent.trace.AiCostCalculator costs =
                new cn.edu.seig.vibemusic.service.agent.trace.AiCostCalculator();
        AgentTraceSettings settings = new AgentTraceSettings();
        ReflectionTestUtils.setField(settings, "enabled", true);
        ReflectionTestUtils.setField(settings, "captureContent", true);
        cn.edu.seig.vibemusic.service.agent.trace.AiTraceQueryService service =
                new cn.edu.seig.vibemusic.service.agent.trace.AiTraceQueryService(
                        traceMapper, searchMapper, metricMapper, costs, settings, recorder);

        cn.edu.seig.vibemusic.model.dto.AiTraceQueryDTO query =
                new cn.edu.seig.vibemusic.model.dto.AiTraceQueryDTO();
        query.setUsername("nobody-here");
        var page = service.search(query);
        assertEquals(0, page.getTotal(), "账号不存在必须返回空，不能返回全表");
        assertTrue(page.getItems().isEmpty());
    }

    @Test
    @DisplayName("费用按单次输入 Token 选档：边界命中当前档，跨档分别累加，超最高档不计价")
    void costUsesPerCallTiers() {
        AiCostCalculator costs = configuredCosts();

        // 128K 正好命中第一档，而不是被错误推到第二档。
        assertEquals(0, costs.tierIndexFor(128_000));
        assertEquals(1, costs.tierIndexFor(128_001));
        assertEquals(2, costs.tierIndexFor(1_000_000));
        assertEquals(-1, costs.tierIndexFor(1_000_001));

        AiCostVO result = costs.estimateUsages(List.of(
                AiCostCalculator.Usage.one(128_000L, 1_000L, true),
                AiCostCalculator.Usage.one(128_001L, 1_000L, true),
                AiCostCalculator.Usage.one(1_000_001L, 1_000L, true),
                AiCostCalculator.Usage.one(null, null, false)));

        assertTrue(result.isConfigured());
        assertEquals(2, result.getPricedCalls());
        assertEquals(2, result.getUnpricedCalls());
        assertEquals("按单次请求输入 Token 分档", result.getTier());
        // 0.15*0.128 + 1.5*0.001 + 0.6*0.128001 + 6*0.001
        // 金额按 6 位小数落库，0.1035006 四舍五入为 0.103501。
        assertEquals(0.103501, result.getAmount().doubleValue(), 0.0000001);
        assertEquals(3, result.getTiers().size());
        assertTrue(result.getNote().contains("缺少供应商 Token"));
        assertTrue(result.getNote().contains("超出最高档"));
    }

    @Test
    @DisplayName("多次小请求不会先合并 Token 再被错误推到更高档")
    void multipleSmallCallsDoNotShareTier() {
        AiCostCalculator costs = configuredCosts();
        AiCostVO result = costs.estimateUsages(List.of(
                AiCostCalculator.Usage.one(100_000L, 1_000L, true),
                AiCostCalculator.Usage.one(100_000L, 1_000L, true)));

        assertEquals(2, result.getPricedCalls());
        // 合并成 200K 会错误命中第二档；逐次计算应为 2 * 第一档价格。
        assertEquals(0.033, result.getAmount().doubleValue(), 0.0000001);
    }

    @Test
    @DisplayName("总览费用 SQL 按每个 MODEL Span 独立选档，且识别缺少 Trace 的调用")
    void overviewCostUsesModelSpanTiers() {
        String traceId = "trace_cost_chat";
        jdbc.update("INSERT INTO tb_ai_task_metric(task_id, user_id, chat_id, model, model_calls, tool_calls, "
                        + "tool_failures, input_tokens, output_tokens, token_source, result_count, success, "
                        + "failure_category, output_guarded, latency_ms, created_at) "
                        + "VALUES (?, 1, 'trace_cost_chat', 'qwen-flash', 3, 0, 0, 160, 40, 'PROVIDER', 0, TRUE, "
                        + "'NONE', FALSE, 100, CURRENT_TIMESTAMP)",
                traceId);
        // 两个独立小请求，合并后是 160 Token；仍应各自命中第一档。
        jdbc.update("INSERT INTO tb_ai_trace_span(trace_id, span_id, parent_span_id, chat_id, user_id, "
                        + "span_type, name, status, started_at, ended_at, duration_ms, model, input_tokens, "
                        + "output_tokens, token_source, created_at) "
                        + "VALUES (?, 'cost-model-1', NULL, 'trace_cost_chat', 1, 'MODEL', 'm1', 'OK', "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 10, 'qwen-flash', 80, 20, 'PROVIDER', "
                        + "CURRENT_TIMESTAMP)",
                traceId);
        jdbc.update("INSERT INTO tb_ai_trace_span(trace_id, span_id, parent_span_id, chat_id, user_id, "
                        + "span_type, name, status, started_at, ended_at, duration_ms, model, input_tokens, "
                        + "output_tokens, token_source, created_at) "
                        + "VALUES (?, 'cost-model-2', NULL, 'trace_cost_chat', 1, 'MODEL', 'm2', 'OK', "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 10, 'qwen-flash', 80, 20, 'PROVIDER', "
                        + "CURRENT_TIMESTAMP)",
                traceId);

        AgentTraceSettings settings = new AgentTraceSettings();
        ReflectionTestUtils.setField(settings, "enabled", true);
        ReflectionTestUtils.setField(settings, "captureContent", true);
        AiTraceQueryService service = new AiTraceQueryService(
                traceMapper, searchMapper, metricMapper, configuredCosts(), settings, recorder);

        var overview = service.overview(
                java.time.LocalDateTime.now().minusDays(1),
                java.time.LocalDateTime.now().plusDays(1));
        assertTrue(overview.getCost().isConfigured());
        assertEquals(2, overview.getCost().getPricedCalls());
        assertEquals(1, overview.getCost().getUnpricedCalls(), "任务指标多出的 1 次调用没有 Trace，必须明示未计价");
        assertTrue(overview.getCost().getNote().contains("没有 Trace 明细"));
        // 两次 80 输入 / 20 输出，均命中第一档；SQL 已聚合为 160/40，不能再次乘调用次数。
        assertEquals(0.000084, overview.getCost().getAmount().doubleValue(), 0.0000001);
    }

    @Test
    @DisplayName("MySQL 返回小写 snake_case 聚合列时，tokenSource 与 tierIndex 仍能正确读取")
    void lowercaseSnakeCaseAggregateColumnsAreRead() {
        AiTraceQueryService service = new AiTraceQueryService(
                traceMapper, searchMapper, metricMapper, configuredCosts(),
                new AgentTraceSettings(), recorder);
        Map<String, Object> row = Map.of(
                "token_source", "PROVIDER",
                "tier_index", 0);

        assertEquals("PROVIDER",
                ReflectionTestUtils.invokeMethod(service, "value", row, "tokenSource"));
        Object tierIndex = ReflectionTestUtils.invokeMethod(service, "value", row, "tierIndex");
        assertEquals("0", String.valueOf(tierIndex));
    }

    private static AiCostCalculator configuredCosts() {
        AiCostCalculator costs = new AiCostCalculator();
        costs.setCurrency("CNY");
        costs.setPriceVersion("qwen-flash-test");
        costs.setTiers(List.of(
                tier(128_000L, "0.15", "1.5"),
                tier(256_000L, "0.6", "6"),
                tier(1_000_000L, "1.2", "12")));
        return costs;
    }

    private static AiCostCalculator.Tier tier(long maxInputTokens, String input, String output) {
        AiCostCalculator.Tier tier = new AiCostCalculator.Tier();
        tier.setMaxInputTokens(maxInputTokens);
        tier.setInputPricePerMillion(new java.math.BigDecimal(input));
        tier.setOutputPricePerMillion(new java.math.BigDecimal(output));
        return tier;
    }

    @Test
    @DisplayName("查询区间超上限时拒绝，避免一次扫描整张表")
    void oversizedRangeIsRejected() {
        cn.edu.seig.vibemusic.service.agent.trace.AiTraceQueryService service =
                new cn.edu.seig.vibemusic.service.agent.trace.AiTraceQueryService(
                        traceMapper, searchMapper, metricMapper,
                        new cn.edu.seig.vibemusic.service.agent.trace.AiCostCalculator(),
                        new AgentTraceSettings(), recorder);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.overview(java.time.LocalDateTime.now().minusDays(90),
                        java.time.LocalDateTime.now()));
    }

    private static Map<?, ?> parse(String json) {
        try {
            return AgentJson.MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            fail("属性 JSON 无法解析：" + json);
            return Map.of();
        }
    }
}
