package cn.edu.seig.vibemusic.config;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.dashscope.QwenChatModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;

/**
 * QwenChatModel 的包装器，用于记录发送给 API 的消息，帮助调试 InputRequiredException。
 */
@Slf4j
public class LoggingQwenChatModel implements ChatLanguageModel {

    private final QwenChatModel delegate;

    public LoggingQwenChatModel(QwenChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        log.info("=== generate(List<ChatMessage>) 被调用, 消息数={}, 类型: {} ===",
                messages.size(),
                messages.stream().map(m -> m.type().name()).collect(Collectors.joining(" -> ")));
        if (messages.isEmpty()) {
            log.error("!!! 消息列表为空！这会导致 InputRequiredException !!!");
        }
        return delegate.generate(messages);
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        log.info("=== generate(List<ChatMessage>, tools) 被调用, 消息数={}, 类型: {} ===",
                messages.size(),
                messages.stream().map(m -> m.type().name()).collect(Collectors.joining(" -> ")));
        if (messages.isEmpty()) {
            log.error("!!! 消息列表为空（tools模式）！这会导致 InputRequiredException !!!");
        }
        return delegate.generate(messages, toolSpecifications);
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification toolSpecification) {
        log.info("=== generate(List<ChatMessage>, 单个tool) 被调用, 消息数={}, 类型: {} ===",
                messages.size(),
                messages.stream().map(m -> m.type().name()).collect(Collectors.joining(" -> ")));
        return delegate.generate(messages, toolSpecification);
    }
}
