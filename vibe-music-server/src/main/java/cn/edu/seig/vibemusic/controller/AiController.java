package cn.edu.seig.vibemusic.controller;

import cn.edu.seig.vibemusic.model.dto.AiChatRequest;
import cn.edu.seig.vibemusic.model.entity.ChatMessage;
import cn.edu.seig.vibemusic.security.AiSessionService;
import cn.edu.seig.vibemusic.service.IAiService;
import cn.edu.seig.vibemusic.service.ai.AiAssistantFactory;
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

@Slf4j
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiController {
    private static final String AI_ERROR = "AI 服务暂不可用，请稍后再试";
    private final IAiService aiService;
    private final AiSessionService sessions;
    private final AiAssistantFactory assistants;
    private final ExecutorService aiExecutor;
    private final ScheduledExecutorService aiTimer;

    @PostMapping("/chat")
    public String chat(@Valid @RequestBody AiChatRequest body, HttpServletRequest request,
                       HttpServletResponse response) {
        String chatId = sessions.resolve(request, response);
        AiAssistantFactory.Run run = assistants.create(chatId);
        Future<String> task = null;
        try {
            task = aiExecutor.submit(() -> run.assistant().chat(chatId, body.query()));
            return task.get(assistants.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "AI 回复超时，请稍后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, AI_ERROR);
        } catch (ExecutionException | RejectedExecutionException e) {
            log.warn("AI 请求失败，类型={}", e.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, AI_ERROR);
        } finally {
            run.close();
            if (task != null) task.cancel(true);
        }
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@Valid @RequestBody AiChatRequest body, HttpServletRequest request,
                                 HttpServletResponse response) {
        String chatId = sessions.resolve(request, response);
        AiAssistantFactory.Run run = assistants.create(chatId);
        long timeout = assistants.timeout().toMillis();
        SseEmitter emitter = new SseEmitter(timeout + 1000);
        AtomicBoolean finished = new AtomicBoolean();
        AtomicReference<Future<?>> task = new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> timer = new AtomicReference<>();
        Runnable cancel = () -> {
            finished.set(true);
            run.close();
            if (timer.get() != null) timer.get().cancel(false);
            if (task.get() != null) task.get().cancel(true);
        };
        java.util.function.Consumer<String> fail = message -> {
            if (finished.compareAndSet(false, true)) {
                run.close();
                try { emitter.send(SseEmitter.event().name("error").data(message)); }
                catch (IOException | IllegalStateException ignored) { }
                emitter.complete();
            }
            cancel.run();
        };
        emitter.onCompletion(cancel);
        emitter.onTimeout(() -> fail.accept("AI 回复超时，请稍后重试"));
        emitter.onError(error -> cancel.run());
        try {
            timer.set(aiTimer.schedule(() -> fail.accept("AI 回复超时，请稍后重试"), timeout, TimeUnit.MILLISECONDS));
            task.set(aiExecutor.submit(() -> {
                try {
                    run.budget().check();
                    run.assistant().chatStream(chatId, body.query())
                            .onNext(token -> {
                                if (finished.get()) return;
                                try { emitter.send(SseEmitter.event().name("token").data(token)); }
                                catch (IOException | IllegalStateException e) { fail.accept(AI_ERROR); }
                            })
                            .onComplete(result -> {
                                if (!finished.compareAndSet(false, true)) return;
                                run.close();
                                if (timer.get() != null) timer.get().cancel(false);
                                try {
                                    String text = result.content().text();
                                    emitter.send(SseEmitter.event().name("done").data(text == null ? "" : text));
                                } catch (IOException | IllegalStateException ignored) { }
                                emitter.complete();
                            })
                            .onError(error -> {
                                log.warn("AI 流式请求失败，类型={}", error.getClass().getSimpleName());
                                fail.accept(AI_ERROR);
                            }).start();
                } catch (RuntimeException e) {
                    log.warn("AI 流式请求启动失败，类型={}", e.getClass().getSimpleName());
                    fail.accept(AI_ERROR);
                }
            }));
            if (finished.get()) task.get().cancel(true);
        } catch (RejectedExecutionException e) { fail.accept("AI 服务繁忙，请稍后再试"); }
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
}
