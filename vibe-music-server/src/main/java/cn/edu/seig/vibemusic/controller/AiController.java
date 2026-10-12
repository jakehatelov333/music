package cn.edu.seig.vibemusic.controller;

import cn.edu.seig.vibemusic.model.agent.AgentEvent;
import cn.edu.seig.vibemusic.model.agent.AgentEventSink;
import cn.edu.seig.vibemusic.model.dto.AiChatRequest;
import cn.edu.seig.vibemusic.model.entity.ChatMessage;
import cn.edu.seig.vibemusic.security.AiSessionService;
import cn.edu.seig.vibemusic.security.PlaylistAccessService;
import cn.edu.seig.vibemusic.service.IAiService;
import cn.edu.seig.vibemusic.service.agent.AgentFailureCategory;
import cn.edu.seig.vibemusic.service.agent.AgentMetrics;
import cn.edu.seig.vibemusic.service.agent.AgentMetricsRecorder;
import cn.edu.seig.vibemusic.service.ai.AiAssistantFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 对话入口。
 *
 * <p><b>SSE 协议</b>（事件名 = {@link AgentEvent#type()}，data 一律是纯文本）：
 * <pre>
 *   start     {"taskId","model"}                          Agent 开始处理
 *   tool      {"toolCallId","tool","phase","ok",...}       工具开始/结束
 *   songs     {"toolCallId","kind","total","data":[...]}   结构化歌曲数据
 *   confirm   {"data":{...PendingActionVO}}                等待用户确认
 *   task      {"ok","code","message","data":{指标摘要}}     任务结束汇总
 *   token     &lt;增量文本&gt;                                  沿用既有协议，格式未变
 *   done      &lt;最终文本&gt;                                  沿用既有协议，格式未变
 *   error     &lt;错误说明&gt;                                  沿用既有协议，格式未变
 * </pre>
 *
 * <p><b>向后兼容</b>：{@code token/done/error} 的语义与载荷格式完全保持原样，
 * 新增事件是叠加的，旧前端不会因此报错。
 *
 * <p><b>异常兜底</b>：无论成功、失败、超时还是客户端中途断开，
 * 都会走同一个 {@code settle} 结算路径：写指标、释放会话租约、关闭运行。
 * 因此页面不会因为异常而永久停留在加载态，也不会泄漏会话租约。
 */
@Slf4j
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiController {

    private static final String AI_ERROR = "AI 服务暂不可用，请稍后再试";

    /**
     * 输出侧护栏：模型有时会<b>跳过写工具却仍然声称</b>「已生成预览，请在下方确认卡片中确认」。
     * 数据库是安全的（没调用工具就没有任何写入），但界面会骗用户 —— 让他去找一张不存在的卡片。
     *
     * <p>判定依据是<b>服务端事实</b>而不是模型措辞：
     * <ul>
     *     <li>声称「有待确认卡片」→ 必须本轮真的产生过待确认任务（{@code pendingTaskId != null}）；</li>
     *     <li>声称「已创建成功」→ 必须本轮真的写入过歌单（{@code playlistId != null}）。</li>
     * </ul>
     * 不满足就把话术换成如实说明。这样即使模型说错，用户也不会被误导。
     */
    private static final java.util.regex.Pattern CARD_CLAIM =
            java.util.regex.Pattern.compile("确认卡片|待确认");
    private static final java.util.regex.Pattern CREATED_CLAIM =
            java.util.regex.Pattern.compile("已创建成功|已为你创建|创建成功了|已成功创建");
    private static final String NO_CARD_CORRECTION =
            "抱歉，我这一轮没有真正生成待确认卡片，所以下方不会有卡片可以确认。"
                    + "请再说一次，例如「帮我创建歌单：夜间驾驶，选 3 首周杰伦的歌」，我会先生成预览再请你确认。";
    private static final String NO_CREATE_CORRECTION =
            "需要说明一下：歌单只有在你在确认卡片上点击「确认创建」之后才会真正创建。"
                    + "请以上方卡片的真实状态为准。";

    private final IAiService aiService;
    private final AiSessionService sessions;
    private final AiAssistantFactory assistants;
    private final AgentMetricsRecorder metricRecorder;
    private final ExecutorService aiExecutor;
    private final ScheduledExecutorService aiTimer;

    @PostMapping("/chat")
    public String chat(@Valid @RequestBody AiChatRequest body, HttpServletRequest request,
                       HttpServletResponse response) {
        String chatId = sessions.resolve(request, response);
        AiAssistantFactory.Run run = assistants.create(chatId, PlaylistAccessService.optionalUserId(),
                AgentEventSink.NOOP);
        AgentMetrics metrics = run.metrics();
        Future<String> task = null;
        try {
            task = aiExecutor.submit(() -> run.assistant().chat(chatId, body.query()));
            String text = task.get(assistants.timeout().toMillis(), TimeUnit.MILLISECONDS);
            metrics.finish(true, AgentFailureCategory.NONE, null);
            return guardFinalText(text, metrics);
        } catch (TimeoutException e) {
            metrics.finish(false, AgentFailureCategory.TIMEOUT, "AI 回复超时");
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "AI 回复超时，请稍后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            metrics.finish(false, AgentFailureCategory.INTERRUPTED, "请求被中断");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, AI_ERROR);
        } catch (ExecutionException | RejectedExecutionException e) {
            log.warn("AI 请求失败，类型={}", e.getClass().getSimpleName());
            metrics.finish(false, classify(e.getCause() == null ? e : e.getCause()), "模型或工具调用失败");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, AI_ERROR);
        } finally {
            metricRecorder.record(metrics);
            run.close();
            if (task != null) task.cancel(true);
        }
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public SseEmitter streamChat(@Valid @RequestBody AiChatRequest body, HttpServletRequest request,
                                 HttpServletResponse response) {
        String chatId = sessions.resolve(request, response);
        Long userId = PlaylistAccessService.optionalUserId();
        long timeout = assistants.timeout().toMillis();
        SseEmitter emitter = new SseEmitter(timeout + 1000);
        AtomicBoolean closed = new AtomicBoolean();
        SseChannel channel = new SseChannel(emitter, closed);

        AiAssistantFactory.Run run;
        try {
            run = assistants.create(chatId, userId, channel);
        } catch (RuntimeException e) {
            log.warn("AI 流式会话创建失败，类型={}", e.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前会话正在处理另一条消息，请稍后再试");
        }
        StreamSession session = new StreamSession(emitter, channel, run);

        emitter.onCompletion(() -> {
            if (!session.settled()) {
                // 客户端主动断开：任务未完成，记为 INTERRUPTED 而不是成功。
                session.settle(false, AgentFailureCategory.INTERRUPTED, "客户端断开连接");
            }
        });
        emitter.onTimeout(() -> {
            if (session.settle(false, AgentFailureCategory.TIMEOUT, "AI 回复超时")) {
                channel.send("task", json(AgentEvent.task(run.taskId(), false,
                        AgentFailureCategory.TIMEOUT, "AI 回复超时", run.metrics().summary())));
                channel.send("error", "AI 回复超时，请稍后重试");
                emitter.complete();
            }
        });
        emitter.onError(error -> {
            session.settle(false, AgentFailureCategory.INTERRUPTED, "SSE 连接异常");
            channel.close();
        });

        try {
            session.scheduleTimer(aiTimer);
            session.setTask(aiExecutor.submit(() -> {
                try {
                    run.budget().check();
                    run.announce();
                    run.assistant().chatStream(chatId, body.query())
                            .onNext(token -> channel.send("token", token))
                            .onComplete(result -> {
                                if (session.settle(true, AgentFailureCategory.NONE, null)) {
                                    // 先做输出护栏，再生成 task 汇总，保证 outputGuarded 出现在事件里。
                                    String text = guardFinalText(result.content().text() == null ? ""
                                            : result.content().text(), run.metrics());
                                    channel.send("task", json(AgentEvent.task(run.taskId(), true,
                                            AgentFailureCategory.NONE, "任务完成", run.metrics().summary())));
                                    channel.send("done", text);
                                }
                                channel.close();
                                emitter.complete();
                            })
                            .onError(error -> {
                                String category = classify(error);
                                log.warn("AI 流式请求失败，类型={} 分类={}", error.getClass().getSimpleName(), category);
                                if (session.settle(false, category, categoryMessage(category))) {
                                    channel.send("task", json(AgentEvent.task(run.taskId(), false, category,
                                            categoryMessage(category), run.metrics().summary())));
                                    channel.send("error", AI_ERROR);
                                }
                                channel.close();
                                emitter.complete();
                            }).start();
                } catch (RuntimeException e) {
                    String category = classify(e);
                    log.warn("AI 流式请求启动失败，类型={} 分类={}", e.getClass().getSimpleName(), category);
                    if (session.settle(false, category, categoryMessage(category))) {
                        channel.send("task", json(AgentEvent.task(run.taskId(), false, category,
                                categoryMessage(category), run.metrics().summary())));
                        channel.send("error", AI_ERROR);
                    }
                    channel.close();
                    emitter.complete();
                }
            }));
        } catch (RejectedExecutionException e) {
            if (session.settle(false, AgentFailureCategory.MODEL_ERROR, "AI 服务繁忙")) {
                channel.send("error", "AI 服务繁忙，请稍后再试");
            }
            emitter.complete();
        }
        return emitter;
    }

    @GetMapping("/chat/history")
    public List<ChatMessage> history(HttpServletRequest request, HttpServletResponse response) {
        return aiService.getMessagesByChatId(sessions.resolve(request, response));
    }

    @GetMapping("/chat/history/page")
    public List<ChatMessage> historyPage(@RequestParam(defaultValue = "1") @Min(1) @Max(100000) int page,
                                       @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
                                       HttpServletRequest request, HttpServletResponse response) {
        return aiService.getMessagesByChatId(sessions.resolve(request, response), page, size).getRecords();
    }

    @GetMapping("/chat/recent")
    public List<ChatMessage> recent(@RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit,
                                   HttpServletRequest request, HttpServletResponse response) {
        return aiService.getRecentMessages(sessions.resolve(request, response), limit);
    }

    @DeleteMapping("/chat/clear")
    public String clear(HttpServletRequest request, HttpServletResponse response) {
        aiService.deleteMessagesByChatId(sessions.resolve(request, response));
        return "聊天记录已清空";
    }

    @GetMapping("/chat/stats")
    public String stats(HttpServletRequest request, HttpServletResponse response) {
        return "消息总数: " + aiService.countMessagesByChatId(sessions.resolve(request, response));
    }

    /** 当前登录用户自己的 Agent 指标，便于演示时直接查看真实耗时与 Token 用量。 */
    @GetMapping("/chat/metrics")
    public List<cn.edu.seig.vibemusic.model.entity.AiTaskMetric> metrics(
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit) {
        return metricRecorder.recentForUser(PlaylistAccessService.requireUserId(), limit);
    }

    // ------------------------------------------------------------------ 内部

    /** 一次流式任务的结算器：保证“写指标 + 释放租约 + 关闭事件通道”只执行一次。 */
    private final class StreamSession {
        private final SseEmitter emitter;
        private final SseChannel channel;
        private final AiAssistantFactory.Run run;
        private final AtomicBoolean settled = new AtomicBoolean();
        private final AtomicReference<Future<?>> task = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> timer = new AtomicReference<>();

        private StreamSession(SseEmitter emitter, SseChannel channel, AiAssistantFactory.Run run) {
            this.emitter = emitter;
            this.channel = channel;
            this.run = run;
        }

        boolean settled() {
            return settled.get();
        }

        void scheduleTimer(ScheduledExecutorService scheduler) {
            timer.set(scheduler.schedule(() -> {
                if (settle(false, AgentFailureCategory.TIMEOUT, "AI 回复超时")) {
                    channel.send("task", json(AgentEvent.task(run.taskId(), false,
                            AgentFailureCategory.TIMEOUT, "AI 回复超时", run.metrics().summary())));
                    channel.send("error", "AI 回复超时，请稍后重试");
                }
                channel.close();
                emitter.complete();
            }, assistants.timeout().toMillis(), TimeUnit.MILLISECONDS));
        }

        void setTask(Future<?> value) {
            task.set(value);
            if (settled.get()) value.cancel(true);
        }

        boolean settle(boolean success, String category, String message) {
            if (!settled.compareAndSet(false, true)) return false;
            run.metrics().finish(success, category, message);
            ScheduledFuture<?> scheduled = timer.get();
            if (scheduled != null) scheduled.cancel(false);
            Future<?> running = task.get();
            if (running != null) running.cancel(true);
            run.close();
            metricRecorder.record(run.metrics());
            return true;
        }
    }

    /** 线程安全的 SSE 出口；一旦底层连接不可用就整体关闭，后续事件静默丢弃。 */
    private static final class SseChannel implements AgentEventSink {
        private final SseEmitter emitter;
        private final AtomicBoolean closed;
        private final Object lock = new Object();

        private SseChannel(SseEmitter emitter, AtomicBoolean closed) {
            this.emitter = emitter;
            this.closed = closed;
        }

        @Override
        public void emit(AgentEvent event) {
            send(event.type(), json(event));
        }

        void send(String name, String data) {
            if (closed.get()) return;
            synchronized (lock) {
                if (closed.get()) return;
                try {
                    emitter.send(SseEmitter.event().name(name).data(data == null ? "" : data));
                } catch (IOException | IllegalStateException e) {
                    closed.set(true);
                }
            }
        }

        void close() {
            closed.set(true);
        }
    }

    private static String json(Object value) {
        // 复用 Agent 协议层的序列化器：它注册了 JSR-310 模块，
        // 否则待确认任务里的 LocalDateTime 会让 confirm 事件退化成空对象。
        return cn.edu.seig.vibemusic.model.agent.AgentJson.write(value);
    }

    /**
     * 用服务端事实校正模型的最终话术（见 {@link #CARD_CLAIM} 的说明）。
     * 纯函数，便于单测；{@code metrics} 为空时不做校正（无法判定）。
     */
    public static String guardFinalText(String text, AgentMetrics metrics) {
        if (text == null || text.isBlank() || metrics == null) return text;
        if (CARD_CLAIM.matcher(text).find() && metrics.pendingTaskId() == null) {
            log.info("输出护栏命中：模型声称有待确认卡片，但本轮未产生待确认任务");
            metrics.markOutputGuarded();
            return NO_CARD_CORRECTION;
        }
        if (CREATED_CLAIM.matcher(text).find() && metrics.playlistId() == null) {
            log.info("输出护栏命中：模型声称已创建歌单，但本轮没有真实写入");
            metrics.markOutputGuarded();
            return NO_CREATE_CORRECTION;
        }
        return text;
    }

    /**
     * 失败分类。工具内部的异常已经在工具适配层被转成 {@code ToolResult}，
     * 因此能逃逸到这里的异常基本来自模型调用或会话/预算控制。
     */
    private static String classify(Throwable error) {
        if (error == null) return AgentFailureCategory.UNKNOWN;
        String message = error.getMessage() == null ? "" : error.getMessage();
        String type = error.getClass().getSimpleName();
        if (message.contains("工具调用上限") || message.contains("模型调用上限")) {
            return AgentFailureCategory.TOOL_ERROR;
        }
        if (message.contains("超时") || type.contains("Timeout")) {
            return AgentFailureCategory.TIMEOUT;
        }
        if (type.contains("ResponseStatus")) {
            return AgentFailureCategory.BUSINESS_ERROR;
        }
        if (type.contains("IllegalArgument") || type.contains("IllegalState")) {
            return AgentFailureCategory.BUSINESS_ERROR;
        }
        return AgentFailureCategory.MODEL_ERROR;
    }

    /**
     * 对外只暴露分类级的人类可读说明，绝不回传原始异常文本 ——     * 供应商异常里可能带请求 ID、URL 甚至凭据片段，落库或推给前端都是泄漏面。
     * 真实异常类型仍然记在服务端日志里用于排障。
     */
    private static String categoryMessage(String category) {
        return switch (category) {
            case AgentFailureCategory.MODEL_ERROR -> "模型服务调用失败，请稍后重试";
            case AgentFailureCategory.TOOL_ERROR -> "工具调用失败，请稍后重试";
            case AgentFailureCategory.TIMEOUT -> "AI 回复超时，请稍后重试";
            case AgentFailureCategory.INTERRUPTED -> "连接已中断";
            case AgentFailureCategory.BUSINESS_ERROR -> "业务处理失败，请稍后重试";
            default -> AI_ERROR;
        };
    }
}
