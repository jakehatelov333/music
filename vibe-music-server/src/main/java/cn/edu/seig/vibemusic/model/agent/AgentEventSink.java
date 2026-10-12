package cn.edu.seig.vibemusic.model.agent;

/**
 * 事件出口。由传输层（SSE）实现，Agent 层只依赖这个函数式接口，不感知 Servlet 细节。
 */
@FunctionalInterface
public interface AgentEventSink {
    AgentEventSink NOOP = event -> { };

    void emit(AgentEvent event);
}
