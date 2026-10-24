package cn.edu.seig.vibemusic.controller;

import cn.edu.seig.vibemusic.model.agent.AgentEvent;
import cn.edu.seig.vibemusic.model.agent.AgentEventSink;
import cn.edu.seig.vibemusic.model.dto.AiChatRequest;
import cn.edu.seig.vibemusic.model.entity.ChatMessage;
import cn.edu.seig.vibemusic.model.entity.UserMusicProfile;
import cn.edu.seig.vibemusic.security.AiSessionService;
import cn.edu.seig.vibemusic.security.PlaylistAccessService;
import cn.edu.seig.vibemusic.service.IAiService;
import cn.edu.seig.vibemusic.service.agent.AgentFailureCategory;
import cn.edu.seig.vibemusic.service.agent.AgentMetrics;
import cn.edu.seig.vibemusic.service.agent.AgentMetricsRecorder;
import cn.edu.seig.vibemusic.service.agent.ChatSummaryService;
import cn.edu.seig.vibemusic.service.agent.ConversationService;
import cn.edu.seig.vibemusic.service.agent.MusicTasteSummaryService;
import cn.edu.seig.vibemusic.service.agent.UserMusicProfileService;
import cn.edu.seig.vibemusic.service.agent.trace.AgentTrace;
import cn.edu.seig.vibemusic.service.agent.trace.AgentTraceRecorder;
import cn.edu.seig.vibemusic.service.agent.trace.TraceSnapshotBuilder;
import cn.edu.seig.vibemusic.service.ai.AiAssistantFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class AiController {

    private static final String AI_ERROR = "AI 服务暂不可用，请稍后再试";

    /**
     * 输出侧护栏：模型有时会<b>跳过写工具却仍然声称</b>「已生成预览，请在下方确认卡片中确认」。
     * 数据库是安全的（没调用工具就没有任何写入），但界面会骗用户 —— 让他去找一张不存在的卡片。
     *
     * <p>判定依据是<b>服务端事实</b>而不是模型措辞：
     * <ul>
     *     <li>声称「有待确认卡片」→ 必须本轮真的产生过待确认任务（{@code pendingTaskId != null}）；</li>
     *     <li>声称「已创建成功」→ 必须本轮真的写入过歌单（{@code playlistId != null}）；</li>
     *     <li>声称「已按播放历史过滤」→ 当前<b>没有任何工具具备这个能力</b>，一律视为虚构服务端行为。</li>
     * </ul>
     * 不满足就把话术换成如实说明。这样即使模型说错，用户也不会被误导。
     */
    /**
     * 第一类护栏：模型声称「有待确认卡片 / 已准备好预览」。
     *
     * <p><b>为什么不能只匹配「确认卡片」「待确认」两个词：</b>这两个词覆盖的是<b>措辞</b>，
     * 不是<b>语义</b>。2026-10-22 在线评测的真实轨迹里，模型只调了 {@code searchSongs}，
     * 却回复「我已为你准备了歌单预览，包含这首曲目。是否确认创建？」—— 一个关键词都不含，
     * 于是原样透传给用户，让他去找一张根本不存在的卡片。
     *
     * <p>现在按「完成态的写操作声明」匹配：{@code 已/已经} + 准备/生成/做好 + 预览/歌单/草稿。
     * 刻意要求完成态标记，这样「我可以帮你准备预览，需要吗」「点击确认后才会创建歌单」
     * 这类描述能力或条件的说法不会被误伤（它们没有完成态）。
     */
    private static final java.util.regex.Pattern CARD_CLAIM =
            java.util.regex.Pattern.compile(
                    "确认卡片|待确认"
                            // 正常语序：已为你准备了歌单预览 / 我为你准备了一个歌单
                            // 必须带完成态锚点：「我可以帮你准备预览」是描述能力，不是声称做过。
                            // 锚点有两种，各自独立成支：
                            //   ①「已」——已为你生成歌单草稿（不需要「了」）
                            //   ②「为你/给你/帮你 + 动词 + 了」——我为你准备了一个歌单
                            //      （省略「已」，但「了」表明完成态；没有「了」的
                            //        「我可以帮你准备预览」是描述能力，必须放行）
                            + "|已(?:经)?[^。；\\n]{0,8}(?:准备|生成|做好|整理)[^。；\\n]{0,6}"
                            + "(?:预览|歌单|草稿|候选)"
                            + "|(?:为你|给你|帮你)[^。；\\n]{0,6}(?:准备|生成|做好|整理)"
                            + "[^。；\\n]{0,4}了[^。；\\n]{0,6}(?:预览|歌单|草稿|候选)"
                            // 倒装语序：歌单预览已经准备好了
                            + "|(?:预览|歌单|草稿)[^。；\\n]{0,4}已(?:经)?[^。；\\n]{0,4}"
                            + "(?:准备|生成|做好|整理)");
    private static final java.util.regex.Pattern CREATED_CLAIM =
            java.util.regex.Pattern.compile("已创建成功|已为你创建|创建成功了|已成功创建");

    /**
     * 第三类护栏：模型声称<b>服务端按播放历史做了过滤/排除</b>。
     *
     * <p><b>为什么这类说法一定是假的：</b>当前没有任何工具能按播放历史排除歌曲 ——
     * {@code searchPlaylistCandidates} 排除的是「本会话近期歌单已用过的歌」，
     * 与 {@code tb_play_history} 无关；{@code getRecentPlayedSongs} 只读不筛。
     * 因此只要模型说「已为您过滤掉播放历史里听过的歌」，它描述的就不是服务端行为。
     *
     * <p><b>真实事故：</b>工具返回 5 首（其中 3 首已在播放记录里），模型自行挑掉 3 首，
     * 回复「已为您过滤掉播放历史中已听过的歌曲，以下是推荐的 2 首新曲」。推理正确、归属错误，
     * 且与界面上的歌曲卡片（仍是 5 首）直接矛盾。旧护栏只认「卡片」与「创建」两类断言，
     * 这句话原样透传给了用户。
     *
     * <p><b>两种语序都要覆盖：</b>「已为您<u>过滤</u>掉播放历史里<u>听过</u>的歌」与
     * 「已从<u>播放历史</u>中<u>移除</u>这几首」是同一种虚构，只是动词在前还是在后。
     *
     * <p><b>为什么必须带完成态标记（已/系统/自动）：</b>判定要确定性，不能误伤
     * 「你是想让我去掉听过的歌吗」「我可以按播放历史过滤，需要吗」这类复述与建议 ——
     * 它们没有完成态，不构成"声称服务端做过"。
     */
    private static final java.util.regex.Pattern PLAYED_FILTER_CLAIM =
            java.util.regex.Pattern.compile(
                    "(?:已(?:经)?(?:为您|帮你|替你)?|系统已|已自动)[^。；\\n]{0,8}"
                            + "(?:过滤|排除|剔除|筛掉|移除|去掉|挑掉|删掉)[^。；\\n]{0,12}"
                            + "(?:播放历史|听过|已听|播放记录)"
                            + "|"
                            + "(?:已(?:经)?|系统已|已自动)[^。；\\n]{0,8}"
                            + "(?:播放历史|播放记录)[^。；\\n]{0,12}"
                            + "(?:过滤|排除|剔除|筛掉|移除|去掉|挑掉|删掉)");
    private static final String NO_CARD_CORRECTION =
            "抱歉，我这一轮没有真正生成待确认卡片，所以下方不会有卡片可以确认。"
                    + "请再说一次，例如「帮我创建歌单：夜间驾驶，选 3 首周杰伦的歌」，我会先生成预览再请你确认。";
    private static final String NO_CREATE_CORRECTION =
            "需要说明一下：歌单只有在你在确认卡片上点击「确认创建」之后才会真正创建。"
                    + "请以上方卡片的真实状态为准。";
    private static final String NO_PLAYED_FILTER_CORRECTION =
            "需要澄清一下：我这一轮并没有真的按播放历史做过滤。"
                    + "上面卡片里的歌不会因为「你已经听过」而减少——卡片显示的就是服务端返回的全部结果。"
                    + "如果你要的是只保留没听过的歌，请让我重新检索一遍，那样卡片里才会真的只剩没听过的。";

    private final IAiService aiService;
    private final AiSessionService sessions;
    private final AiAssistantFactory assistants;
    private final AgentMetricsRecorder metricRecorder;
    private final ExecutorService aiExecutor;
    private final ScheduledExecutorService aiTimer;
    private final ChatSummaryService chatSummaries;
    private final MusicTasteSummaryService tasteSummaries;
    private final UserMusicProfileService musicProfiles;
    private final ConversationService conversations;
    /**
     * Trace 采集器。允许为 {@code null}（既有测试直接 new 本控制器时不传），
     * 为 null 时全部采集调用跳过，SSE 协议与业务行为完全不变。
     */
    private final AgentTraceRecorder traceRecorder;
    private final TraceSnapshotBuilder traceSnapshots;

    /**
     * 不带追踪的构造器：既有测试直接 new 本控制器时保持原样。
     * 生产环境由 Spring 注入真实的 {@link AgentTraceRecorder}。
     */
    public AiController(IAiService aiService, AiSessionService sessions, AiAssistantFactory assistants,
                        AgentMetricsRecorder metricRecorder, ExecutorService aiExecutor,
                        ScheduledExecutorService aiTimer, ChatSummaryService chatSummaries,
                        MusicTasteSummaryService tasteSummaries, UserMusicProfileService musicProfiles,
                        ConversationService conversations) {
        this(aiService, sessions, assistants, metricRecorder, aiExecutor, aiTimer, chatSummaries,
                tasteSummaries, musicProfiles, conversations, null, null);
    }

    @PostMapping("/chat")
    public String chat(@Valid @RequestBody AiChatRequest body, HttpServletRequest request,
                       HttpServletResponse response) {
        String chatId = sessions.resolve(request, response, body.chatId(),
                Boolean.TRUE.equals(body.newConversation()));
        AiAssistantFactory.Run run = assistants.create(chatId, PlaylistAccessService.optionalUserId(),
                AgentEventSink.NOOP);
        AgentMetrics metrics = run.metrics();
        Future<String> task = null;
        AgentTrace trace = run.trace();
        if (traceRecorder != null) traceRecorder.userQuery(trace, body.query());
        try {
            task = aiExecutor.submit(() -> run.assistant().chat(chatId, body.query()));
            String text = task.get(assistants.timeout().toMillis(), TimeUnit.MILLISECONDS);
            metrics.finish(true, AgentFailureCategory.NONE, null);
            String guarded = guardFinalText(text, metrics);
            recordGuard(trace, text, guarded, metrics);
            return guarded;
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
            recordTrace(trace, metrics);
            run.close();
            if (task != null) task.cancel(true);
            // 放在 run.close() 之后：会话租约已释放，摘要任务读到的历史才是完整的。
            scheduleMemoryMaintenance(chatId, PlaylistAccessService.optionalUserId());
        }
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public SseEmitter streamChat(@Valid @RequestBody AiChatRequest body, HttpServletRequest request,
                                 HttpServletResponse response) {
        String chatId = sessions.resolve(request, response, body.chatId(),
                Boolean.TRUE.equals(body.newConversation()));
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
        if (traceRecorder != null) traceRecorder.userQuery(run.trace(), body.query());

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
                                String raw = result.content().text() == null ? "" : result.content().text();
                                // 护栏在结算**之前**跑：它会置 outputGuarded，
                                // 而指标是在 settle 里落库的。顺序反了，指标就永远记不到护栏命中。
                                java.util.concurrent.atomic.AtomicReference<String> finalText =
                                        new java.util.concurrent.atomic.AtomicReference<>(raw);
                                boolean first = session.settle(true, AgentFailureCategory.NONE, null,
                                        () -> {
                                            String guarded = guardFinalText(raw, run.metrics());
                                            finalText.set(guarded);
                                            recordGuard(run.trace(), raw, guarded, run.metrics());
                                        });
                                if (first) {
                                    channel.send("task", json(AgentEvent.task(run.taskId(), true,
                                            AgentFailureCategory.NONE, "任务完成", run.metrics().summary())));
                                    channel.send("done", finalText.get());
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
    public List<ChatMessage> history(@RequestParam(required = false) String chatId,
                                     HttpServletRequest request, HttpServletResponse response) {
        return aiService.getMessagesByChatId(sessions.resolve(request, response, chatId, false));
    }

    @GetMapping("/chat/history/page")
    public List<ChatMessage> historyPage(@RequestParam(defaultValue = "1") @Min(1) @Max(100000) int page,
                                       @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
                                       @RequestParam(required = false) String chatId,
                                       HttpServletRequest request, HttpServletResponse response) {
        return aiService.getMessagesByChatId(sessions.resolve(request, response, chatId, false), page, size)
                .getRecords();
    }

    @GetMapping("/chat/recent")
    public List<ChatMessage> recent(@RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit,
                                   @RequestParam(required = false) String chatId,
                                   HttpServletRequest request, HttpServletResponse response) {
        return aiService.getRecentMessages(sessions.resolve(request, response, chatId, false), limit);
    }

    /**
     * 清空当前会话的消息。
     *
     * <p>登录用户走 {@link ConversationService#clear}：除了消息与摘要，
     * 还会取消该会话下仍待确认的任务 —— 清空了记录却留下一张「点击即可建歌单」的卡片，
     * 是用户看不见但仍然可执行的残留动作。游客没有待确认任务（创建歌单要求登录），
     * 因此直接清消息即可。
     */
    @DeleteMapping("/chat/clear")
    public String clear(@RequestParam(required = false) String chatId,
                        HttpServletRequest request, HttpServletResponse response) {
        String resolved = sessions.resolve(request, response, chatId, false);
        Long userId = PlaylistAccessService.optionalUserId();
        if (userId != null) conversations.clear(userId, resolved);
        else aiService.deleteMessagesByChatId(resolved);
        return "聊天记录已清空";
    }

    @GetMapping("/chat/stats")
    public String stats(@RequestParam(required = false) String chatId,
                        HttpServletRequest request, HttpServletResponse response) {
        return "消息总数: "
                + aiService.countMessagesByChatId(sessions.resolve(request, response, chatId, false));
    }

    /** 当前登录用户自己的 Agent 指标，便于演示时直接查看真实耗时与 Token 用量。 */
    @GetMapping("/chat/metrics")
    public List<cn.edu.seig.vibemusic.model.entity.AiTaskMetric> metrics(
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit) {
        return metricRecorder.recentForUser(PlaylistAccessService.requireUserId(), limit);
    }

    /**
     * 当前登录用户自己的音乐画像。只读、只返回自己的数据。
     * 游客访问会因 {@code requireUserId()} 返回 401 —— 画像是用户数据，不对匿名开放。
     */
    @GetMapping("/profile")
    public UserMusicProfile profile() {
        return musicProfiles.read(PlaylistAccessService.requireUserId());
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 记录护栏结论（仅在真的替换了文本时才算"命中"）。
     *
     * <p>判定方式与指标口径一致：{@code guardFinalText} 返回的对象与原文不同即视为命中。
     * 这样追踪记录与 {@code tb_ai_task_metric.output_guarded} 不会出现两套说法。
     */
    private void recordGuard(AgentTrace trace, String raw, String guarded, AgentMetrics metrics) {
        if (trace == null || traceRecorder == null) return;
        boolean guardedHit = raw != null && guarded != null && !raw.equals(guarded);
        traceRecorder.guardApplied(trace, guardedHit, guarded, guardedHit ? "话术与服务端事实不符，已替换" : null);
    }

    /**
     * 结束并落库 Trace。
     *
     * <p><b>为什么放在 finally / settle 里：</b>成功、失败、超时、客户端断开都要有记录，
     * 否则"用户说卡住了"这类问题在追踪里完全查不到。采集失败只计数，不影响用户回复。
     */
    private void recordTrace(AgentTrace trace, AgentMetrics metrics) {
        if (trace == null || traceRecorder == null) return;
        try {
            traceRecorder.finish(trace, metrics.success(), metrics.failureCategory(),
                    metrics.failureMessage(), metrics.latencyMillis());
            traceRecorder.flush(trace);
        } catch (RuntimeException e) {
            // 采集器内部已吞异常并计数；这里再兜一层，确保任何情况下都不影响响应。
            log.warn("AI Trace 结算失败，已忽略，类型={}", e.getClass().getSimpleName());
        }
    }

    /**
     * 会话结束后异步维护记忆与画像。
     *
     * <p><b>为什么放在这里而不是工具里：</b>摘要与画像都是"事后整理"，
     * 与本次回答的正确性无关。放在请求路径里只会让用户多等一次模型调用。
     *
     * <p>延迟 1 秒是为了错开流式连接的收尾；失败只记日志
     * （两个服务内部均已兜底），绝不影响已经返回给用户的结果。
     */
    private void scheduleMemoryMaintenance(String chatId, Long userId) {
        try {
            aiTimer.schedule(() -> {
                chatSummaries.maybeSummarize(chatId);
                if (userId != null) tasteSummaries.refreshSummary(userId);
            }, 1, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            log.warn("记忆维护任务提交失败，跳过本次整理");
        }
    }

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
            return settle(success, category, message, null);
        }

        /**
         * 结算，并在写指标<b>之前</b>执行一个动作（输出护栏就是它唯一的调用方）。
         *
         * <p><b>为什么必须留这个钩子：</b>护栏会置 {@code outputGuarded} 这个事实位，
         * 而指标是在结算里落库的。早先的写法是「先 settle（内部已落库）再跑护栏」，
         * 结果 2026-10-21 那次「已为您过滤掉播放历史中已听过的歌曲」的真实事故里，
         * 护栏即使生效了，{@code tb_ai_task_metric.output_guarded} 也仍然是 0 ——
         * 指标变成了永远查不出问题的摆设。
         *
         * <p>钩子里的异常一律吞掉：护栏是附加保障，不能让它的 bug 反过来吞掉一次正常的回复。
         */
        boolean settle(boolean success, String category, String message, Runnable beforeRecord) {
            if (!settled.compareAndSet(false, true)) return false;
            if (beforeRecord != null) {
                try {
                    beforeRecord.run();
                } catch (RuntimeException e) {
                    log.warn("结算前置动作失败，已跳过，类型={}", e.getClass().getSimpleName());
                }
            }
            run.metrics().finish(success, category, message);
            ScheduledFuture<?> scheduled = timer.get();
            if (scheduled != null) scheduled.cancel(false);
            Future<?> running = task.get();
            if (running != null) running.cancel(true);
            run.close();
            metricRecorder.record(run.metrics());
            recordTrace(run.trace(), run.metrics());
            // 结算之后异步整理记忆与画像：这是"事后整理"，与本次回答的正确性无关，
            // 放在请求路径里只会让用户多等一次模型调用。
            scheduleMemoryMaintenance(run.metrics().chatId(), run.metrics().userId());
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
     *
     * <p>顺序有讲究：先判「卡片」「创建」这两类会导致<b>误操作</b>的断言，
     * 再判「按播放历史过滤」这类<b>误描述</b>的断言。前者是安全问题，后者是一致性问题。
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
        if (PLAYED_FILTER_CLAIM.matcher(text).find() && !metrics.playedHistoryFiltered()) {
            log.info("输出护栏命中：模型声称已按播放历史过滤，但本轮没有对应的服务端排除");
            metrics.markOutputGuarded();
            return NO_PLAYED_FILTER_CORRECTION;
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
