package cn.edu.seig.vibemusic.agent.eval;

import cn.edu.seig.vibemusic.agent.AbstractAgentTest;
import cn.edu.seig.vibemusic.controller.AiController;
import cn.edu.seig.vibemusic.handler.GlobalExceptionHandler;
import cn.edu.seig.vibemusic.memory.MysqlChatMemoryStore;
import cn.edu.seig.vibemusic.model.entity.AiTaskMetric;
import cn.edu.seig.vibemusic.security.AiSessionService;
import cn.edu.seig.vibemusic.service.ai.AiAssistantFactory;
import cn.edu.seig.vibemusic.service.agent.AgentFailureCategory;
import cn.edu.seig.vibemusic.service.agent.AgentMetricsRecorder;
import cn.edu.seig.vibemusic.service.agent.PendingActionService;
import cn.edu.seig.vibemusic.tool.MusicTools;
import cn.edu.seig.vibemusic.tool.PlaylistActionTools;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 第 9 项验收：SSE 流式协议在<b>真实控制器</b>上的端到端行为。
 *
 * <p>这里跑的是真正的 {@link AiController}：事件名、事件载荷、结束顺序、
 * 异常兜底、指标落库都在真实链路上验证。模型仍用脚本替身（无外网）。
 */
class AgentSseProtocolTest extends AbstractAgentTest {

    @Autowired private MusicTools musicTools;
    @Autowired private PlaylistActionTools playlistActionTools;
    @Autowired private PendingActionService pendingActionService;
    @Autowired private AgentMetricsRecorder recorder;

    private MockMvc mvc;
    private java.util.concurrent.ExecutorService executor;
    private ScheduledExecutorService timer;

    @BeforeEach
    void setUpMvc() {
        executor = Executors.newFixedThreadPool(2);
        timer = Executors.newSingleThreadScheduledExecutor();
        mvc = MockMvcBuilders.standaloneSetup(controller(factory()))
                .addFilter(new org.springframework.web.filter.CharacterEncodingFilter("UTF-8", true))
                .setMessageConverters(
                        // 独立 MockMvc 不会装配 Boot 的 UTF-8 StringHttpMessageConverter，
                        // 这里显式指定，否则 SSE 里的中文会被写成 '?'（生产链路由 Boot 保证 UTF-8）。
                        new org.springframework.http.converter.StringHttpMessageConverter(
                                java.nio.charset.StandardCharsets.UTF_8),
                        new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        timer.shutdownNow();
        ThreadLocalUtil.remove();
    }

    /**
     * 构造真实控制器。记忆维护（摘要/画像）用 mock 替身：
     * 本测试聚焦 SSE 协议，摘要与画像由 ChatSummaryServiceTest 等专项覆盖。
     */
    private AiController controller(AiAssistantFactory factory) {
        // 本测试全程以用户 1 登录，会话标识由 ConversationService 决定 ——
        // 这里用替身固定返回默认会话，让 SSE 协议本身成为唯一的被测对象；
        // 归属判定由 AiControllerSecurityTest 与 ConversationServiceTest 专项覆盖。
        cn.edu.seig.vibemusic.service.agent.ConversationService conversations =
                mock(cn.edu.seig.vibemusic.service.agent.ConversationService.class);
        org.mockito.Mockito.when(conversations.resolve(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn("user_chat_id_1");
        return new AiController(mock(cn.edu.seig.vibemusic.service.IAiService.class),
                new AiSessionService(mock(org.springframework.data.redis.core.StringRedisTemplate.class), conversations),
                factory, recorder, executor, timer,
                mock(cn.edu.seig.vibemusic.service.agent.ChatSummaryService.class),
                mock(cn.edu.seig.vibemusic.service.agent.MusicTasteSummaryService.class),
                mock(cn.edu.seig.vibemusic.service.agent.UserMusicProfileService.class),
                conversations);
    }

    private AiAssistantFactory factory() {
        AiAssistantFactory factory = new AiAssistantFactory(
                AgentEvalHarness.scriptedModel(List.of(), false),
                AgentEvalHarness.scriptedStreaming(List.of(), false),
                new MysqlChatMemoryStore(AgentEvalHarness.fakeAiService(), new ObjectMapper()),
                musicTools, playlistActionTools, new cn.edu.seig.vibemusic.service.ai.StaticSystemPrompt());
        ReflectionTestUtils.setField(factory, "timeoutSeconds", 15);
        ReflectionTestUtils.setField(factory, "maxToolCalls", 4);
        ReflectionTestUtils.setField(factory, "modelName", "qwen-flash-sse");
        return factory;
    }

    /** 用给定的脚本重建控制器（每个用例的脚本不同）。 */
    private void rebuildWithScript(List<EvalCase.Step> script, boolean failMidStream) {
        AiAssistantFactory factory = new AiAssistantFactory(
                AgentEvalHarness.scriptedModel(script, failMidStream),
                AgentEvalHarness.scriptedStreaming(script, failMidStream),
                new MysqlChatMemoryStore(AgentEvalHarness.fakeAiService(), new ObjectMapper()),
                musicTools, playlistActionTools, new cn.edu.seig.vibemusic.service.ai.StaticSystemPrompt());
        ReflectionTestUtils.setField(factory, "timeoutSeconds", 15);
        ReflectionTestUtils.setField(factory, "maxToolCalls", 4);
        ReflectionTestUtils.setField(factory, "modelName", "qwen-flash-sse");
        mvc = MockMvcBuilders.standaloneSetup(controller(factory))
                .addFilter(new org.springframework.web.filter.CharacterEncodingFilter("UTF-8", true))
                .setMessageConverters(
                        // 独立 MockMvc 不会装配 Boot 的 UTF-8 StringHttpMessageConverter，
                        // 这里显式指定，否则 SSE 里的中文会被写成 '?'（生产链路由 Boot 保证 UTF-8）。
                        new org.springframework.http.converter.StringHttpMessageConverter(
                                java.nio.charset.StandardCharsets.UTF_8),
                        new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private String stream(String query) throws Exception {
        MvcResult result = mvc.perform(post("/ai/chat/stream").contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of("query", query))))
                .andExpect(request().asyncStarted()).andReturn();
        // 独立 MockMvc 不加载 CharacterEncodingFilter，显式按 UTF-8 读取，与生产链路一致。
        return mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static EvalCase.Step tool(String name, Map<String, Object> args) {
        return new EvalCase.Step(name, args, null, false);
    }

    private static EvalCase.Step text(String value) {
        return new EvalCase.Step(null, null, value, false);
    }

    @Test
    @DisplayName("SSE 事件顺序为 start → tool → songs → task → done，且 task 载荷带 taskId 与指标")
    void streamEmitsProtocolEventsInOrder() throws Exception {
        login(1L);
        rebuildWithScript(List.of(tool("searchSongs", Map.of("keyword", "夜色")), text("为你找到夜色温柔")), false);
        String body = stream("搜一下夜色");

        for (String event : List.of("event:start", "event:tool", "event:songs", "event:task", "event:done")) {
            assertTrue(body.contains(event), "缺少事件 " + event + "，实际响应：\n" + body);
        }
        assertTrue(body.indexOf("event:start") < body.indexOf("event:tool"));
        assertTrue(body.indexOf("event:tool") < body.indexOf("event:songs"));
        assertTrue(body.indexOf("event:songs") < body.indexOf("event:task"));
        assertTrue(body.indexOf("event:task") < body.indexOf("event:done"));
        assertTrue(body.contains("\"taskId\""), "task 事件应带 taskId");
        assertTrue(body.contains("\"toolCallId\""), "tool 事件应带 toolCallId");
        assertTrue(body.contains("\"songId\":10"), "songs 事件应带真实 songId");
        assertTrue(body.contains("\"tokenSource\":\"PROVIDER\""), "指标应标注 Token 来源");
        assertEquals(0, count("tb_playlist"), "只搜索不应创建歌单");
    }

    @Test
    @DisplayName("生成歌单预览时推送 confirm 事件，但仍不创建歌单")
    void streamEmitsConfirmEventAndStillWritesNothing() throws Exception {
        login(1L);
        rebuildWithScript(List.of(
                tool("searchSongs", Map.of("keyword", "夜色")),
                tool("preparePlaylistCreation", Map.of("title", "夜色歌单", "introduction", "",
                        "style", "流行", "songIds", "10")),
                text("已生成预览，请在下方确认")), false);
        String body = stream("帮我建一个夜色歌单");

        assertTrue(body.contains("event:confirm"), "应推送确认事件：\n" + body);
        assertTrue(body.contains("\"status\":\"PENDING\""));
        assertTrue(body.contains("夜色歌单"));
        // task 事件里带 pendingTaskId，可从「对话任务」追到「待确认任务 → 真实歌单」。
        assertTrue(body.contains("\"pendingTaskId\""), "task 事件应带待确认任务 ID：\n" + body);
        assertEquals(1, count("tb_agent_pending_action"));
        assertEquals(0, count("tb_playlist"), "确认之前不得创建歌单");
        assertEquals(0, count("tb_playlist_binding"));
    }

    @Test
    @DisplayName("流式失败时推送 task(false) + error，并把失败分类写入指标表")
    void streamFailureIsClassifiedAndRecorded() throws Exception {
        login(1L);
        rebuildWithScript(List.of(new EvalCase.Step(null, null, "upstream 503", true)), false);
        String body = stream("你好");

        assertTrue(body.contains("event:error"), "应推送 error 事件：\n" + body);
        assertTrue(body.contains("event:task"));
        assertTrue(body.contains(AgentFailureCategory.MODEL_ERROR), "失败分类应出现在 task 事件里");

        AiTaskMetric metric = latestMetricRow();
        assertNotNull(metric, "失败任务也要落指标");
        assertEquals(false, metric.getSuccess());
        assertEquals(AgentFailureCategory.MODEL_ERROR, metric.getFailureCategory());
        assertNotNull(metric.getLatencyMs());
    }

    @Test
    @DisplayName("供应商异常原文不会出现在 SSE 响应里，避免凭据或内部细节泄漏")
    void rawProviderErrorTextIsNotLeaked() throws Exception {
        login(1L);
        rebuildWithScript(List.of(new EvalCase.Step(null, null, "api-key-secret-SHOULD-NOT-LEAK", true)), false);
        String body = stream("你好");
        assertFalse(body.contains("api-key-secret-SHOULD-NOT-LEAK"),
                "原始异常文本不得进入响应：\n" + body);
    }

    @Test
    @DisplayName("模型跳过写工具却声称「请在下方的确认卡片中确认」时，done 事件被服务端事实校正")
    void previewClaimWithoutToolCallIsCorrected() throws Exception {
        login(1L);
        // 脚本只检索、不调用 preparePlaylistCreation，但回复里谎称有确认卡片。
        rebuildWithScript(List.of(
                tool("searchSongsByArtist", Map.of("artistName", "Ariana Grande")),
                text("已为你生成歌单预览，请在下方确认卡片中确认或取消：歌单名称：夜间驾驶，歌曲数量：3 首")), false);
        String body = stream("帮我建一个歌单叫夜间驾驶");

        // done 是权威最终文本；流式 token 只是渐进预览，护栏只作用于 done（见 agent-protocol.md）。
        String done = donePayload(body);
        assertFalse(done.contains("请在下方确认卡片中确认"),
                "没有真实待确认任务时，不得把「请确认卡片」的话术作为最终文本透给用户：" + done);
        assertTrue(done.contains("没有真正生成待确认卡片"), "应替换为如实说明：" + done);
        assertTrue(body.contains("\"outputGuarded\":true"), "task 事件应标记护栏命中：\n" + body);
        assertEquals(0, count("tb_agent_pending_action"));
        assertEquals(0, count("tb_playlist"));
    }

    @Test
    @DisplayName("真的生成了待确认任务时，正常话术不被护栏改动")
    void legitimatePreviewClaimIsKept() throws Exception {
        login(1L);
        rebuildWithScript(List.of(
                tool("searchSongs", Map.of("keyword", "夜色")),
                tool("preparePlaylistCreation", Map.of("title", "夜色歌单", "introduction", "",
                        "style", "流行", "songIds", "10")),
                text("已生成预览，请在下方确认卡片中确认或取消")), false);
        String body = stream("帮我建一个夜色歌单");

        assertTrue(donePayload(body).contains("已生成预览，请在下方确认卡片中确认或取消"),
                "合法话术不应被替换：" + donePayload(body));
        assertTrue(body.contains("\"outputGuarded\":false"));
        assertEquals(1, count("tb_agent_pending_action"));
    }

    /** 取出 SSE 流里 done 事件的 data（权威最终文本）。 */
    private static String donePayload(String body) {
        for (String block : body.split("\n\n")) {
            String[] lines = block.split("\n");
            boolean isDone = false;
            StringBuilder data = new StringBuilder();
            for (String line : lines) {
                if (line.startsWith("event:")) isDone = "done".equals(line.substring(6).trim());
                else if (line.startsWith("data:")) data.append(line.substring(5).replaceFirst("^ ", ""));
            }
            if (isDone) return data.toString();
        }
        return "";
    }

    @Test
    @DisplayName("输出护栏是纯函数：无服务端事实时替换，有事实时放行")
    void guardFinalTextIsFactDriven() {
        cn.edu.seig.vibemusic.service.agent.AgentMetrics empty =
                new cn.edu.seig.vibemusic.service.agent.AgentMetrics("t", 1L, "c", "m");
        assertEquals("已生成预览，请在下方确认卡片中确认",
                cn.edu.seig.vibemusic.controller.AiController.guardFinalText(
                        "已生成预览，请在下方确认卡片中确认", null), "metrics 缺失时不判定");
        assertTrue(cn.edu.seig.vibemusic.controller.AiController.guardFinalText(
                "已生成预览，请在下方确认卡片中确认", empty).contains("没有真正生成待确认卡片"));
        assertTrue(cn.edu.seig.vibemusic.controller.AiController.guardFinalText(
                "已创建成功", empty).contains("点击「确认创建」"));
        assertEquals("今天天气不错",
                cn.edu.seig.vibemusic.controller.AiController.guardFinalText("今天天气不错", empty));

        empty.pendingAction("task-1");
        assertEquals("已生成预览，请在下方确认卡片中确认",
                cn.edu.seig.vibemusic.controller.AiController.guardFinalText(
                        "已生成预览，请在下方确认卡片中确认", empty), "有真实待确认任务时应放行");
    }

    private AiTaskMetric latestMetricRow() {
        List<AiTaskMetric> rows = jdbc.query(
                "SELECT * FROM tb_ai_task_metric ORDER BY id DESC LIMIT 1", (rs, rowNum) -> {
                    AiTaskMetric metric = new AiTaskMetric();
                    metric.setTaskId(rs.getString("task_id"));
                    metric.setSuccess(rs.getBoolean("success"));
                    metric.setFailureCategory(rs.getString("failure_category"));
                    metric.setModelCalls(rs.getInt("model_calls"));
                    metric.setToolCalls(rs.getInt("tool_calls"));
                    metric.setTokenSource(rs.getString("token_source"));
                    metric.setLatencyMs(rs.getLong("latency_ms"));
                    return metric;
                });
        return rows.isEmpty() ? null : rows.get(0);
    }
}
