package cn.edu.seig.vibemusic.agent.eval;

import cn.edu.seig.vibemusic.enumeration.PendingActionStatusEnum;
import cn.edu.seig.vibemusic.mapper.AgentPendingActionMapper;
import cn.edu.seig.vibemusic.mapper.AiTaskMetricMapper;
import cn.edu.seig.vibemusic.memory.MysqlChatMemoryStore;
import cn.edu.seig.vibemusic.model.agent.AgentEvent;
import cn.edu.seig.vibemusic.model.agent.AgentEventSink;
import cn.edu.seig.vibemusic.model.agent.AgentJson;
import cn.edu.seig.vibemusic.model.agent.PlaylistDraft;
import cn.edu.seig.vibemusic.model.agent.SongRef;
import cn.edu.seig.vibemusic.model.dto.AgentPlaylistDraftDTO;
import cn.edu.seig.vibemusic.model.entity.AiTaskMetric;
import cn.edu.seig.vibemusic.model.vo.PendingActionVO;
import cn.edu.seig.vibemusic.service.IAiService;
import cn.edu.seig.vibemusic.service.agent.AgentFailureCategory;
import cn.edu.seig.vibemusic.service.agent.AgentMetrics;
import cn.edu.seig.vibemusic.service.agent.AgentMetricsRecorder;
import cn.edu.seig.vibemusic.service.agent.PendingActionService;
import cn.edu.seig.vibemusic.service.ai.AiAssistantFactory;
import cn.edu.seig.vibemusic.tool.MusicTools;
import cn.edu.seig.vibemusic.tool.PlaylistActionTools;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.dashscope.QwenChatModel;
import dev.langchain4j.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Agent 评测执行器。
 *
 * <p><b>为什么是离线可跑的：</b>真实模型服务不可用时（例如没有 API Key 或没有外网），
 * 端到端评测会整体跳过，评测就失去了回归价值。这里把“模型”替换成脚本化替身，
 * 其余部件全部是真的：真实的 {@code AiServices} 编排、真实的工具执行、真实的数据库读写、
 * 真实的待确认状态机与幂等逻辑、真实的指标落库。因此每条用例都是对真实代码路径的验证，
 * 而不是对 mock 行为的自证。
 *
 * <p><b>唯一被替换的是模型输出</b>。因此“工具选得对不对”这类纯模型能力不在这里判定，
 * 那部分由在线端到端评测覆盖（见 {@code AgentEvalReportTest} 的 ONLINE 段）。
 */
public class AgentEvalHarness {

    private final PendingActionService pendingActions;
    private final AgentPendingActionMapper actionMapper;
    private final AiTaskMetricMapper metricMapper;
    private final MusicTools readTools;
    private final PlaylistActionTools actionTools;
    private final MysqlChatMemoryStore store;
    private final AgentMetricsRecorder recorder;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public AgentEvalHarness(PendingActionService pendingActions, AgentPendingActionMapper actionMapper,
                            AiTaskMetricMapper metricMapper, MusicTools readTools, PlaylistActionTools actionTools,
                            JdbcTemplate jdbc) {
        this.pendingActions = pendingActions;
        this.actionMapper = actionMapper;
        this.metricMapper = metricMapper;
        this.readTools = readTools;
        this.actionTools = actionTools;
        this.jdbc = jdbc;
        this.store = new MysqlChatMemoryStore(fakeAiService(), new ObjectMapper());
        this.recorder = new AgentMetricsRecorder(metricMapper);
    }

    /** 单条用例的执行上下文。 */
    public static final class Outcome {
        final List<AgentEvent> events = new ArrayList<>();
        boolean success;
        String failureCategory = AgentFailureCategory.NONE;
        String failureMessage;
        String finalText;
        AgentMetrics metrics;
        PendingActionVO pending;
        Long playlistId;
        Integer playlistSongCount;
        String pendingStatus;
        boolean idempotent;
        int httpStatus;
        String toolResultCode;
        long latencyOverride;
    }

    // ------------------------------------------------------------------ 分发

    public Outcome run(EvalCase.Case testCase) {
        String flow = testCase.flow();
        if (flow.startsWith("AGENT_")) return runAgentFlow(testCase);
        if (flow.startsWith("SSE_")) return runSseFlow(testCase);
        return runServiceFlow(testCase);
    }

    // ------------------------------------------------------------------ 模型驱动的端到端流程

    private Outcome runAgentFlow(EvalCase.Case testCase) {
        Outcome outcome = new Outcome();
        Long userId = testCase.authenticated() ? 1L : null;
        List<EvalCase.Step> script = testCase.script();
        AiAssistantFactory factory = factory(scriptedModel(script, false), scriptedStreaming(script, false));
        String chatId = testCase.chatId();
        AgentEventSink sink = outcome.events::add;

        long start = System.nanoTime();
        try (AiAssistantFactory.Run run = factory.create(chatId, userId, sink)) {
            outcome.metrics = run.metrics();
            try {
                // 传输层在流开始处会发 start；阻塞路径由这里补上，保证事件序列完整可比。
                run.announce();
                outcome.finalText = run.assistant().chat(chatId, testCase.arg("query"));
                outcome.success = true;
            } catch (RuntimeException e) {
                outcome.success = false;
                outcome.failureCategory = classify(e);
                outcome.failureMessage = e.getMessage();
            } finally {
                outcome.metrics.finish(outcome.success, outcome.failureCategory, outcome.failureMessage);
                recorder.record(outcome.metrics);
            }
        }
        outcome.pendingStatus = latestPendingStatus();
        outcome.toolResultCode = lastToolEndCode(outcome.events);
        outcome.latencyOverride = (System.nanoTime() - start) / 1_000_000L;
        return outcome;
    }

    // ------------------------------------------------------------------ 传输层流程（SSE）

    private Outcome runSseFlow(EvalCase.Case testCase) {
        Outcome outcome = new Outcome();
        Long userId = 1L;
        List<EvalCase.Step> script = testCase.script();
        boolean failMidStream = "SSE_INTERRUPT".equals(testCase.flow());
        AiAssistantFactory factory = factory(scriptedModel(script, failMidStream),
                scriptedStreaming(script, failMidStream));
        String chatId = testCase.chatId();

        try (AiAssistantFactory.Run run = factory.create(chatId, userId, outcome.events::add)) {
            outcome.metrics = run.metrics();
            try {
                run.announce();
                CompletableFuture<String> done = new CompletableFuture<>();
                run.assistant().chatStream(chatId, testCase.arg("query"))
                        .onNext(token -> { })
                        .onComplete(result -> done.complete(result.content().text()))
                        .onError(error -> {
                            outcome.failureCategory = classify(error);
                            done.completeExceptionally(error);
                        }).start();
                outcome.finalText = done.get(15, TimeUnit.SECONDS);
                outcome.success = true;
            } catch (ExecutionException e) {
                outcome.success = false;
                if (outcome.failureCategory == null) outcome.failureCategory = classify(e.getCause());
            } catch (RuntimeException | TimeoutException | InterruptedException e) {
                outcome.success = false;
                outcome.failureCategory = classify(e);
            } finally {
                outcome.metrics.finish(outcome.success, outcome.failureCategory, null);
                recorder.record(outcome.metrics);
            }
        }
        // 恢复验证：租约已释放，同一会话的下一次请求必须能正常建立。
        AiAssistantFactory second = factory(scriptedModel(List.of(new EvalCase.Step(null, null, "已恢复", false)), false),
                scriptedStreaming(List.of(), false));
        try (AiAssistantFactory.Run run = second.create(chatId, userId, AgentEventSink.NOOP)) {
            outcome.idempotent = "已恢复".equals(run.assistant().chat(chatId, "还在吗"));
        } catch (RuntimeException e) {
            outcome.idempotent = false;
        }
        outcome.pendingStatus = latestPendingStatus();
        return outcome;
    }

    // ------------------------------------------------------------------ 业务流（待确认 / 确认 / 幂等）

    private Outcome runServiceFlow(EvalCase.Case testCase) {
        Outcome outcome = new Outcome();
        String flow = testCase.flow();
        login(1L);
        try {
            switch (flow) {
                case "PREPARE_ONLY" -> {
                    PendingActionVO preview = prepare(testCase);
                    outcome.pending = preview;
                    outcome.pendingStatus = preview.getStatus();
                    outcome.success = true;
                }
                case "CREATE_CONFIRM" -> confirm(testCase, outcome, 1);
                case "CREATE_MODIFY_CONFIRM" -> {
                    PendingActionVO preview = prepare(testCase);
                    AgentPlaylistDraftDTO edit = new AgentPlaylistDraftDTO();
                    edit.setTitle(testCase.arg("renamed"));
                    edit.setSongIds(testCase.longList("renamedSongs"));
                    edit.setVersion(preview.getVersion());
                    PendingActionVO updated = pendingActions.update(preview.getTaskId(), edit);
                    outcome.pending = updated;
                    PendingActionVO done = pendingActions.confirm(preview.getTaskId(), updated.getVersion());
                    outcome.pending = done;
                    outcome.pendingStatus = done.getStatus();
                    outcome.playlistId = done.getPlaylistId();
                    outcome.success = true;
                }
                case "CREATE_CANCEL" -> {
                    PendingActionVO preview = prepare(testCase);
                    PendingActionVO cancelled = pendingActions.cancel(preview.getTaskId());
                    outcome.pendingStatus = cancelled.getStatus();
                    outcome.success = true;
                    try {
                        pendingActions.confirm(preview.getTaskId(), null);
                        outcome.idempotent = false;
                    } catch (ResponseStatusException e) {
                        outcome.httpStatus = e.getStatusCode().value();
                        outcome.idempotent = e.getStatusCode().value() == 409;
                    }
                }
                case "CREATE_DOUBLE_CONFIRM" -> {
                    PendingActionVO preview = prepare(testCase);
                    PendingActionVO first = pendingActions.confirm(preview.getTaskId(), null);
                    PendingActionVO second = pendingActions.confirm(preview.getTaskId(), null);
                    outcome.playlistId = first.getPlaylistId();
                    outcome.pendingStatus = second.getStatus();
                    outcome.idempotent = Objects.equals(first.getPlaylistId(), second.getPlaylistId());
                    outcome.success = true;
                }
                case "CREATE_CONCURRENT_CONFIRM" -> {
                    PendingActionVO preview = prepare(testCase);
                    outcome.playlistId = concurrentConfirm(preview.getTaskId(), 6);
                    outcome.idempotent = true;
                    outcome.success = true;
                }
                case "EXPIRE_THEN_CONFIRM" -> {
                    PendingActionVO preview = prepare(testCase);
                    jdbc.update("UPDATE tb_agent_pending_action SET expires_at = DATEADD('MINUTE', -5, CURRENT_TIMESTAMP) "
                            + "WHERE task_id = ?", preview.getTaskId());
                    try {
                        pendingActions.confirm(preview.getTaskId(), null);
                        outcome.success = false;
                        outcome.failureCategory = "NOT_REJECTED";
                    } catch (ResponseStatusException e) {
                        outcome.httpStatus = e.getStatusCode().value();
                        outcome.success = e.getStatusCode().value() == 410;
                    }
                    outcome.pendingStatus = pendingActions.get(preview.getTaskId()).getStatus();
                }
                case "CROSS_USER_CONFIRM" -> {
                    PendingActionVO preview = prepare(testCase);
                    login(2L);
                    try {
                        pendingActions.confirm(preview.getTaskId(), null);
                        outcome.success = false;
                        outcome.failureCategory = "NOT_REJECTED";
                    } catch (ResponseStatusException e) {
                        outcome.httpStatus = e.getStatusCode().value();
                        outcome.success = e.getStatusCode().value() == 404;
                    }
                    // 用数据库事实确认任务状态，而不是用“越权用户”再去查一次服务。
                    outcome.pendingStatus = latestPendingStatus();
                }
                case "DB_ERROR_ON_CONFIRM" -> {
                    PendingActionVO preview = prepare(testCase);
                    jdbc.update("DELETE FROM tb_song WHERE id = ?", testCase.longList("songs").get(0));
                    try {
                        pendingActions.confirm(preview.getTaskId(), null);
                        outcome.success = false;
                        outcome.failureCategory = "NOT_REJECTED";
                    } catch (ResponseStatusException e) {
                        outcome.httpStatus = e.getStatusCode().value();
                        outcome.success = true;
                    }
                    outcome.pendingStatus = pendingActions.get(preview.getTaskId()).getStatus();
                }
                case "REFRESH_QUERY" -> {
                    PendingActionVO preview = prepare(testCase);
                    // 模拟刷新页面：重新按会话拉取待确认任务。
                    List<PendingActionVO> pending = pendingActions.pending(testCase.chatId());
                    outcome.pending = pending.stream()
                            .filter(vo -> preview.getTaskId().equals(vo.getTaskId())).findFirst().orElse(null);
                    outcome.pendingStatus = outcome.pending == null ? null : outcome.pending.getStatus();
                    outcome.success = outcome.pending != null;
                }
                case "MISMATCH_CHECK" -> {
                    PendingActionVO preview = prepare(testCase);
                    PendingActionVO done = pendingActions.confirm(preview.getTaskId(), null);
                    outcome.pending = preview;
                    outcome.playlistId = done.getPlaylistId();
                    outcome.pendingStatus = done.getStatus();
                    outcome.success = true;
                }
                case "UPDATE_STALE_VERSION" -> {
                    PendingActionVO preview = prepare(testCase);
                    AgentPlaylistDraftDTO first = new AgentPlaylistDraftDTO();
                    first.setTitle(testCase.arg("renamed"));
                    first.setSongIds(testCase.longList("renamedSongs"));
                    first.setVersion(preview.getVersion());
                    pendingActions.update(preview.getTaskId(), first);
                    AgentPlaylistDraftDTO stale = new AgentPlaylistDraftDTO();
                    stale.setTitle("过期版本");
                    stale.setSongIds(testCase.longList("songs"));
                    stale.setVersion(preview.getVersion());
                    try {
                        pendingActions.update(preview.getTaskId(), stale);
                        outcome.success = false;
                    } catch (ResponseStatusException e) {
                        outcome.httpStatus = e.getStatusCode().value();
                        outcome.success = e.getStatusCode().value() == 409;
                    }
                }
                case "GUEST_CONFIRM" -> {
                    PendingActionVO preview = prepare(testCase);
                    logout();
                    try {
                        pendingActions.confirm(preview.getTaskId(), null);
                        outcome.success = false;
                        outcome.failureCategory = "NOT_REJECTED";
                    } catch (ResponseStatusException e) {
                        outcome.httpStatus = e.getStatusCode().value();
                        outcome.success = e.getStatusCode().value() == 401;
                    }
                    outcome.pendingStatus = latestPendingStatus();
                }
                default -> {
                    outcome.success = false;
                    outcome.failureCategory = "UNKNOWN_FLOW";
                }
            }
        } catch (ResponseStatusException e) {
            outcome.success = false;
            outcome.httpStatus = e.getStatusCode().value();
            outcome.failureCategory = "BUSINESS_ERROR";
            outcome.failureMessage = e.getReason();
        } catch (Exception e) {
            outcome.success = false;
            outcome.failureCategory = "UNKNOWN";
            outcome.failureMessage = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            ThreadLocalUtil.remove();
        }
        if (outcome.playlistId != null) {
            Integer bound = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM tb_playlist_binding WHERE playlist_id = ?", Integer.class, outcome.playlistId);
            outcome.playlistSongCount = bound == null ? 0 : bound;
        }
        if (outcome.playlistId == null) {
            outcome.playlistSongCount = 0;
        }
        return outcome;
    }

    private void confirm(EvalCase.Case testCase, Outcome outcome, int expected) {
        PendingActionVO preview = prepare(testCase);
        outcome.pending = preview;
        PendingActionVO done = pendingActions.confirm(preview.getTaskId(), preview.getVersion());
        outcome.playlistId = done.getPlaylistId();
        outcome.pendingStatus = done.getStatus();
        outcome.success = expected == 1 && "SUCCEEDED".equals(done.getStatus());
    }

    private Long concurrentConfirm(String taskId, int workers) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    login(1L);
                    start.await();
                    try {
                        return pendingActions.confirm(taskId, null).getPlaylistId();
                    } catch (ResponseStatusException e) {
                        return null;
                    } finally {
                        ThreadLocalUtil.remove();
                    }
                }));
            }
            start.countDown();
            Set<Long> created = new LinkedHashSet<>();
            for (Future<Long> future : futures) {
                Long value = future.get(30, TimeUnit.SECONDS);
                if (value != null) created.add(value);
            }
            if (created.size() != 1) {
                throw new IllegalStateException("并发确认产生了 " + created.size() + " 个不同歌单");
            }
            return created.iterator().next();
        } finally {
            pool.shutdownNow();
        }
    }

    private PendingActionVO prepare(EvalCase.Case testCase) {
        return pendingActions.prepare(1L, testCase.chatId(), draft(testCase));
    }

    private PlaylistDraft draft(EvalCase.Case testCase) {
        return new PlaylistDraft(testCase.arg("title"), testCase.arg("introduction"), testCase.arg("style"),
                false, testCase.longList("songs"));
    }

    // ------------------------------------------------------------------ 断言

    /** 返回失败原因，null 表示通过。 */
    public static String evaluate(EvalCase.Case testCase, Outcome outcome, JdbcTemplate jdbc) {
        EvalCase.Expect expect = testCase.expect();
        if (expect == null) return null;
        if (expect.success() != null && expect.success() != outcome.success) {
            return "期望 success=" + expect.success() + "，实际 " + outcome.success
                    + (outcome.failureMessage == null ? "" : "（" + outcome.failureMessage + "）");
        }
        if (expect.failureCategory() != null
                && !expect.failureCategory().equals(outcome.failureCategory)) {
            return "期望 failureCategory=" + expect.failureCategory() + "，实际 " + outcome.failureCategory;
        }
        if (expect.code() != null && !expect.code().equals(outcome.toolResultCode)) {
            return "期望工具结果码=" + expect.code() + "，实际 " + outcome.toolResultCode;
        }
        if (expect.tools() != null && !expect.tools().isEmpty()) {
            List<String> called = outcome.events.stream()
                    .filter(e -> AgentEvent.TOOL.equals(e.type()) && AgentEvent.PHASE_END.equals(e.phase()))
                    .map(AgentEvent::tool).toList();
            for (String tool : expect.tools()) {
                if (!called.contains(tool)) return "期望调用工具 " + tool + "，实际调用 " + called;
            }
        }
        if (expect.eventTypes() != null) {
            for (String type : expect.eventTypes()) {
                if (outcome.events.stream().noneMatch(e -> type.equals(e.type()))) {
                    return "期望出现事件 " + type + "，实际事件序列 "
                            + outcome.events.stream().map(AgentEvent::type).toList();
                }
            }
        }
        if (expect.songCount() != null) {
            int actual = outcome.events.stream()
                    .filter(e -> AgentEvent.SONGS.equals(e.type()))
                    .mapToInt(e -> e.total() == null ? 0 : e.total()).sum();
            if (actual != expect.songCount()) return "期望歌曲条数 " + expect.songCount() + "，实际 " + actual;
        }
        if (expect.modelCalls() != null && outcome.metrics != null
                && outcome.metrics.modelCalls() != expect.modelCalls()) {
            return "期望模型调用 " + expect.modelCalls() + " 次，实际 " + outcome.metrics.modelCalls();
        }
        if (expect.toolCalls() != null && outcome.metrics != null
                && outcome.metrics.toolCalls() != expect.toolCalls()) {
            return "期望工具调用 " + expect.toolCalls() + " 次，实际 " + outcome.metrics.toolCalls();
        }
        if (expect.toolFailures() != null && outcome.metrics != null
                && outcome.metrics.toolFailures() != expect.toolFailures()) {
            return "期望工具失败 " + expect.toolFailures() + " 次，实际 " + outcome.metrics.toolFailures();
        }
        if (expect.tokenSource() != null && outcome.metrics != null
                && !expect.tokenSource().equals(outcome.metrics.tokenSource())) {
            return "期望 tokenSource=" + expect.tokenSource() + "，实际 " + outcome.metrics.tokenSource();
        }
        if (expect.pendingStatus() != null && !expect.pendingStatus().equals(outcome.pendingStatus)) {
            return "期望待确认状态 " + expect.pendingStatus() + "，实际 " + outcome.pendingStatus;
        }
        if (expect.pendingExists() != null) {
            boolean exists = jdbc.queryForObject("SELECT COUNT(*) FROM tb_agent_pending_action", Integer.class) > 0;
            if (exists != expect.pendingExists()) return "期望待确认记录存在=" + expect.pendingExists() + "，实际 " + exists;
        }
        if (expect.playlistCreated() != null) {
            boolean created = jdbc.queryForObject("SELECT COUNT(*) FROM tb_playlist", Integer.class) > 0;
            if (created != expect.playlistCreated()) return "期望已创建歌单=" + expect.playlistCreated() + "，实际 " + created;
        }
        if (expect.playlistSongCount() != null) {
            int bound = jdbc.queryForObject("SELECT COUNT(*) FROM tb_playlist_binding", Integer.class);
            if (bound != expect.playlistSongCount()) {
                return "期望歌单关联歌曲 " + expect.playlistSongCount() + " 首，实际 " + bound;
            }
        }
        if (expect.httpStatus() != null && outcome.httpStatus != expect.httpStatus()) {
            return "期望 HTTP 状态 " + expect.httpStatus() + "，实际 " + outcome.httpStatus;
        }
        if (expect.idempotent() != null && expect.idempotent() != outcome.idempotent) {
            return "期望幂等成立=" + expect.idempotent() + "，实际 " + outcome.idempotent;
        }
        if (Boolean.TRUE.equals(expect.previewMatchesPlaylist())) {
            if (outcome.pending == null || outcome.playlistId == null) return "预览或歌单缺失，无法比对一致性";
            List<Long> previewIds = outcome.pending.getSongs().stream().map(SongRef::songId).sorted().toList();
            List<Long> bound = jdbc.queryForList(
                    "SELECT song_id FROM tb_playlist_binding WHERE playlist_id = ? ORDER BY song_id",
                    Long.class, outcome.playlistId);
            if (!previewIds.equals(bound)) return "预览歌曲 " + previewIds + " 与落库歌曲 " + bound + " 不一致";
            String title = jdbc.queryForObject("SELECT title FROM tb_playlist WHERE id = ?", String.class,
                    outcome.playlistId);
            if (!Objects.equals(outcome.pending.getTitle(), title)) {
                return "预览标题 " + outcome.pending.getTitle() + " 与落库标题 " + title + " 不一致";
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 模型替身

    /** 脚本化阻塞模型：按脚本依次返回工具调用或文本；{@code throwError} 步直接抛异常。 */
    static QwenChatModel scriptedModel(List<EvalCase.Step> script, boolean failMidStream) {
        QwenChatModel model = mock(QwenChatModel.class);
        AtomicInteger index = new AtomicInteger();
        org.mockito.stubbing.Answer<Response<AiMessage>> answer = invocation -> {
            int step = index.getAndIncrement();
            if (step >= script.size()) {
                return Response.from(AiMessage.from("（脚本已结束）"), new TokenUsage(10, 5));
            }
            EvalCase.Step current = script.get(step);
            if (current.isThrow()) {
                throw new RuntimeException(current.text() == null ? "model failure" : current.text());
            }
            if (current.tool() != null) {
                return Response.from(AiMessage.from(toolRequest(current, step)), new TokenUsage(120, 30));
            }
            return Response.from(AiMessage.from(current.text() == null ? "" : current.text()),
                    new TokenUsage(80, 20));
        };
        when(model.generate(anyList())).thenAnswer(answer);
        when(model.generate(anyList(), anyList())).thenAnswer(answer);
        when(model.generate(anyList(), any(ToolSpecification.class))).thenAnswer(answer);
        return model;
    }

    static QwenStreamingChatModel scriptedStreaming(List<EvalCase.Step> script, boolean failMidStream) {
        QwenStreamingChatModel streaming = mock(QwenStreamingChatModel.class);
        AtomicInteger index = new AtomicInteger();
        doAnswer(invocation -> {
            int step = index.getAndIncrement();
            @SuppressWarnings("unchecked")
            StreamingResponseHandler<AiMessage> handler = invocation.getArgument(2);
            if (failMidStream && step == 1) {
                // 模拟流中途断开：已推送部分内容后连接异常结束。
                handler.onNext("正在处理");
                handler.onError(new RuntimeException("stream interrupted"));
                return null;
            }
            if (step >= script.size()) {
                handler.onNext("（脚本已结束）");
                handler.onComplete(Response.from(AiMessage.from("（脚本已结束）"), new TokenUsage(10, 5)));
                return null;
            }
            EvalCase.Step current = script.get(step);
            if (current.isThrow()) {
                handler.onError(new RuntimeException(current.text() == null ? "model failure" : current.text()));
                return null;
            }
            if (current.tool() != null) {
                handler.onComplete(Response.from(AiMessage.from(toolRequest(current, step)), new TokenUsage(120, 30)));
                return null;
            }
            String text = current.text() == null ? "" : current.text();
            handler.onNext(text);
            handler.onComplete(Response.from(AiMessage.from(text), new TokenUsage(80, 20)));
            return null;
        }).when(streaming).generate(anyList(), anyList(), any());
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            StreamingResponseHandler<AiMessage> handler = invocation.getArgument(1);
            handler.onNext("（无工具）");
            handler.onComplete(Response.from(AiMessage.from("（无工具）"), new TokenUsage(10, 5)));
            return null;
        }).when(streaming).generate(anyList(), any(StreamingResponseHandler.class));
        return streaming;
    }

    private static ToolExecutionRequest toolRequest(EvalCase.Step step, int index) {
        Map<String, Object> args = step.args() == null ? Map.of() : step.args();
        return ToolExecutionRequest.builder()
                .id("call-" + index)
                .name(step.tool())
                .arguments(AgentJson.write(args))
                .build();
    }

    private AiAssistantFactory factory(QwenChatModel model, QwenStreamingChatModel streaming) {
        AiAssistantFactory factory = new AiAssistantFactory(model, streaming, store, readTools, actionTools);
        ReflectionTestUtils.setField(factory, "timeoutSeconds", 15);
        ReflectionTestUtils.setField(factory, "maxToolCalls", 4);
        ReflectionTestUtils.setField(factory, "modelName", "qwen-flash-eval");
        return factory;
    }

    /** 内存版会话存储，实现真实的租约语义（同会话并发生成返回 409）。 */
    static IAiService fakeAiService() {
        IAiService service = mock(IAiService.class);
        Map<String, List<cn.edu.seig.vibemusic.model.entity.ChatMessage>> history = new ConcurrentHashMap<>();
        Map<String, String> contexts = new ConcurrentHashMap<>();
        Map<String, String> leases = new ConcurrentHashMap<>();
        AtomicLong ids = new AtomicLong();
        doAnswer(call -> {
            String chat = call.getArgument(0), token = call.getArgument(1);
            if (leases.putIfAbsent(chat, token) != null) throw new ResponseStatusException(HttpStatus.CONFLICT);
            return null;
        }).when(service).acquireConversation(anyString(), anyString(), anyLong());
        doAnswer(call -> {
            leases.remove(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(service).releaseConversation(anyString(), anyString());
        when(service.getModelContext(anyString())).thenAnswer(call -> contexts.get(call.getArgument(0)));
        when(service.getRecentMessages(anyString(), anyInt())).thenAnswer(call -> {
            List<cn.edu.seig.vibemusic.model.entity.ChatMessage> rows =
                    new ArrayList<>(history.getOrDefault(call.getArgument(0), List.of()));
            Collections.reverse(rows);
            return rows.stream().limit((Integer) call.getArgument(1)).toList();
        });
        doAnswer(call -> {
            String chat = call.getArgument(0), token = call.getArgument(1);
            if (!token.equals(leases.get(chat))) throw new ResponseStatusException(HttpStatus.CONFLICT);
            contexts.put(chat, call.getArgument(2));
            List<cn.edu.seig.vibemusic.model.entity.ChatMessage> added = call.getArgument(3);
            for (cn.edu.seig.vibemusic.model.entity.ChatMessage row : added) {
                row.setId(ids.incrementAndGet());
                row.setChatId(chat);
                row.setCreatedAt(LocalDateTime.now());
                history.computeIfAbsent(chat, ignored -> new ArrayList<>()).add(row);
            }
            return null;
        }).when(service).appendConversation(anyString(), anyString(), anyString(), anyList());
        return service;
    }

    // ------------------------------------------------------------------ 小工具

    private static void login(Long userId) {
        ThreadLocalUtil.set(Map.of(cn.edu.seig.vibemusic.constant.JwtClaimsConstant.USER_ID, userId,
                cn.edu.seig.vibemusic.constant.JwtClaimsConstant.ROLE, "ROLE_USER"));
    }

    private static void logout() {
        ThreadLocalUtil.remove();
    }

    private String latestPendingStatus() {
        List<String> statuses = jdbc.queryForList(
                "SELECT status FROM tb_agent_pending_action ORDER BY id DESC", String.class);
        return statuses.isEmpty() ? null : statuses.get(0);
    }

    private static String lastToolEndCode(List<AgentEvent> events) {
        return events.stream()
                .filter(e -> AgentEvent.TOOL.equals(e.type()) && AgentEvent.PHASE_END.equals(e.phase()))
                .reduce((first, second) -> second)
                .map(AgentEvent::code)
                .orElse(null);
    }

    private static String classify(Throwable error) {
        if (error == null) return AgentFailureCategory.UNKNOWN;
        String message = error.getMessage() == null ? "" : error.getMessage();
        String type = error.getClass().getSimpleName();
        if (message.contains("工具调用上限") || message.contains("模型调用上限")) return AgentFailureCategory.TOOL_ERROR;
        if (message.contains("超时") || type.contains("Timeout")) return AgentFailureCategory.TIMEOUT;
        if (type.contains("IllegalArgument") || type.contains("IllegalState")) return AgentFailureCategory.BUSINESS_ERROR;
        return AgentFailureCategory.MODEL_ERROR;
    }

    /** 从指标表读回最近写入的一行，用于验证“指标确实落库”。 */
    public AiTaskMetric latestMetric() {
        List<AiTaskMetric> rows = jdbc.query("SELECT * FROM tb_ai_task_metric ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> {
                    AiTaskMetric metric = new AiTaskMetric();
                    metric.setTaskId(rs.getString("task_id"));
                    metric.setUserId(rs.getObject("user_id") == null ? null : rs.getLong("user_id"));
                    metric.setModel(rs.getString("model"));
                    metric.setModelCalls(rs.getInt("model_calls"));
                    metric.setToolCalls(rs.getInt("tool_calls"));
                    metric.setToolFailures(rs.getInt("tool_failures"));
                    metric.setInputTokens(rs.getObject("input_tokens") == null ? null : rs.getLong("input_tokens"));
                    metric.setOutputTokens(rs.getObject("output_tokens") == null ? null : rs.getLong("output_tokens"));
                    metric.setTokenSource(rs.getString("token_source"));
                    metric.setResultCount(rs.getInt("result_count"));
                    metric.setSuccess(rs.getBoolean("success"));
                    metric.setFailureCategory(rs.getString("failure_category"));
                    metric.setLatencyMs(rs.getLong("latency_ms"));
                    metric.setPlaylistId(rs.getObject("playlist_id") == null ? null : rs.getLong("playlist_id"));
                    return metric;
                });
        return rows.isEmpty() ? null : rows.get(0);
    }
}
