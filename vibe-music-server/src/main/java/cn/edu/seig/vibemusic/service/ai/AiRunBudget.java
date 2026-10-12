package cn.edu.seig.vibemusic.service.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 每次请求独立的预算，模型回调跨线程时仍使用同一个实例。 */
public final class AiRunBudget {
    private final long deadline;
    private final int maxTools;
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();

    public AiRunBudget(Duration timeout, int maxTools) {
        this.deadline = System.nanoTime() + timeout.toNanos();
        this.maxTools = maxTools;
    }

    public void check() {
        if (closed.get() || System.nanoTime() >= deadline) throw new IllegalStateException("AI 任务已结束或超时");
    }

    public void beforeModel() {
        check();
        if (modelCalls.incrementAndGet() > maxTools + 1) throw new IllegalStateException("已达到模型调用上限");
    }

    public void beforeTools(Response<AiMessage> response) {
        check();
        if (response.content().hasToolExecutionRequests()) {
            int count = response.content().toolExecutionRequests().size();
            if (toolCalls.addAndGet(count) > maxTools) throw new IllegalStateException("已达到工具调用上限");
        }
    }

    public void close() { closed.set(true); }
}
