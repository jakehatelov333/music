package cn.edu.seig.vibemusic.service.agent.trace;

import cn.edu.seig.vibemusic.model.agent.AgentJson;
import cn.edu.seig.vibemusic.model.agent.ToolCallScope;
import cn.edu.seig.vibemusic.model.agent.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 给每个工具定义包一层执行器，从而拿到<b>模型侧的原始 Tool Call 请求</b>。
 *
 * <p><b>为什么必须这样做：</b>用 {@code AiServices.tools(Object...)} 时，
 * LangChain4j 在内部创建 {@code DefaultToolExecutor}，模型给的
 * {@code ToolExecutionRequest}（含供应商侧 {@code id} 与原始 {@code arguments}）
 * 只在那个执行器内部可见。工具方法自身只能看到解析后的形参 ——
 * 于是"模型原始请求"与"服务端实际执行"就无法在数据库里对应起来，
 * 只能靠时间接近去猜，而这是不可靠的。
 *
 * <p>改用 {@code tools(Map<ToolSpecification, ToolExecutor>)} 后，执行器由我们提供：
 * <ul>
 *     <li>{@code request.id()} 是模型侧 ID，原样记录；</li>
 *     <li>{@code request.arguments()} 是模型生成的原始 JSON，原样记录；</li>
 *     <li>执行前把 Trace 分配的 span ID 绑定到当前线程（{@link ToolCallScope}），
 *         工具方法内的 {@code nextToolCallId} 会采用它 —— 于是 SSE 的 toolCallId
 *         与数据库的 span_id 是同一个值，<b>关联是构造出来的，不是推断出来的</b>。</li>
 * </ul>
 *
 * <p><b>行为保持完全不变：</b>参数解析、类型转换、异常处理全部委托给 LangChain4j 原生的
 * {@link DefaultToolExecutor}，这里只做观测。参数非法时原生执行器抛出的异常照常向上传播，
 * 由 Agent 的既有错误处理链路接住。
 */
@Slf4j
public final class TracedToolExecutors {

    private TracedToolExecutors() {
    }

    /**
     * @param toolObject 已绑定本次运行上下文的工具实例
     * @param trace      追踪上下文；为 null 时退化为不带追踪的执行器
     */
    public static Map<ToolSpecification, ToolExecutor> wrap(Object toolObject, AgentTrace trace,
                                                            AgentTraceRecorder recorder) {
        Map<ToolSpecification, ToolExecutor> executors = new LinkedHashMap<>();
        for (Method method : toolObject.getClass().getDeclaredMethods()) {
            if (!method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) continue;
            ToolSpecification specification = ToolSpecifications.toolSpecificationFrom(method);
            ToolExecutor delegate = new DefaultToolExecutor(toolObject, method);
            executors.put(specification, traced(delegate, specification.name(), trace, recorder));
        }
        return executors;
    }

    private static ToolExecutor traced(ToolExecutor delegate, String toolName,
                                       AgentTrace trace, AgentTraceRecorder recorder) {
        return (request, memoryId) -> {
            if (trace == null || recorder == null) return delegate.execute(request, memoryId);
            String spanId = recorder.beginToolCall(trace, request, toolName);
            // 工具方法内部据此复用同一个 ID，SSE 与 Trace 由此对齐。
            ToolCallScope.bind(spanId);
            long start = System.nanoTime();
            ToolResult<?> result = null;
            Throwable error = null;
            String raw = null;
            try {
                raw = delegate.execute(request, memoryId);
                result = parseResult(raw);
                return raw;
            } catch (RuntimeException e) {
                error = e;
                throw e;
            } finally {
                ToolCallScope.clear();
                long durationMs = (System.nanoTime() - start) / 1_000_000L;
                recorder.endToolCall(trace, spanId, request, toolName, result, raw, error);
                // 时长以执行器实测为准（工具内部自己记的那次可能更短，因为它不含参数转换）。
                recorder.overrideToolDuration(trace, spanId, durationMs);
            }
        };
    }

    /**
     * 工具返回的是 {@link ToolResult} 的 JSON 文本（项目既有协议）。
     * 解析失败时不构造"假结果"——那会把异常包装成业务成功，这里返回 null 表示
     * "无法解析"，追踪记录会如实标为未知状态。
     */
    private static ToolResult<?> parseResult(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return AgentJson.MAPPER.readValue(raw, ToolResult.class);
        } catch (Exception e) {
            log.debug("工具返回不是标准 ToolResult，按未知状态记录");
            return null;
        }
    }
}
