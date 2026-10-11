package cn.edu.seig.vibemusic.memory;

import cn.edu.seig.vibemusic.model.entity.ChatMessage;
import cn.edu.seig.vibemusic.service.IAiService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class MysqlChatMemoryStore implements ChatMemoryStore {

    private final IAiService aiService;
    private final ObjectMapper objectMapper;

    @Override
    public List<dev.langchain4j.data.message.ChatMessage> getMessages(Object memoryId) {
        String chatId = memoryId.toString();
        List<ChatMessage> entities = aiService.getMessagesByChatId(chatId);
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        // 记录已成功加载的工具调用 id，保证 TOOL_RESULT 只在其对应的工具请求存在时才回放，
        // 避免旧损坏数据产生孤立的 tool 消息导致模型 400
        java.util.Set<String> loadedToolCallIds = new java.util.HashSet<>();

        for (ChatMessage entity : entities) {
            switch (entity.getMessageType()) {
                case USER:
                    messages.add(UserMessage.from(entity.getContent()));
                    break;
                case ASSISTANT:
                    messages.add(AiMessage.from(entity.getContent()));
                    break;
                case SYSTEM:
                    // 系统消息由 @SystemMessage 注解每次注入，无需从数据库加载
                    break;
                case TOOL_REQUEST: {
                    // 从内容重建工具请求：JSON 数组 [{id, name, arguments}, ...]
                    List<ToolExecutionRequest> requests = parseToolRequests(entity.getContent());
                    if (!requests.isEmpty()) {
                        for (ToolExecutionRequest req : requests) {
                            if (req.id() != null) {
                                loadedToolCallIds.add(req.id());
                            }
                        }
                        messages.add(AiMessage.from(requests));
                    }
                    // 旧格式/损坏数据解析失败时跳过，避免非法 arguments 导致模型 400
                    break;
                }
                case TOOL_RESULT: {
                    // 从内容重建工具结果：JSON {id, toolName, text}
                    ToolResultDto resultDto = parseToolResult(entity.getContent());
                    if (resultDto != null && resultDto.getId() != null
                            && loadedToolCallIds.contains(resultDto.getId())) {
                        messages.add(ToolExecutionResultMessage.from(
                                resultDto.getId(),
                                resultDto.getToolName(),
                                resultDto.getText()));
                    }
                    break;
                }
            }
        }

        // 移除末尾没有AI回复的UserMessage（来自之前失败的请求）
        long userCount = messages.stream()
                .filter(m -> m.type() == ChatMessageType.USER).count();
        long aiCount = messages.stream()
                .filter(m -> m.type() == ChatMessageType.AI).count();
        while (userCount > aiCount + 1 && !messages.isEmpty()
                && messages.get(messages.size() - 1).type() == ChatMessageType.USER) {
            messages.remove(messages.size() - 1);
            userCount--;
        }

        log.info("加载聊天历史，chatId={}, 消息数={}, 消息类型={}",
                chatId, messages.size(),
                messages.stream().map(m -> m.type().name()).collect(java.util.stream.Collectors.joining(",")));

        return messages;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateMessages(Object memoryId, List<dev.langchain4j.data.message.ChatMessage> messages) {
        String chatId = memoryId.toString();

        log.info("updateMessages: chatId={}, 传入消息数={}, 类型: {}",
                chatId, messages.size(),
                messages.stream().map(m -> m.type().name()).collect(java.util.stream.Collectors.joining(",")));

        aiService.deleteMessagesByChatId(chatId);

        for (dev.langchain4j.data.message.ChatMessage message : messages) {
            switch (message.type()) {
                case SYSTEM:
                    // 系统消息不持久化到数据库，它由 @SystemMessage 注解在每次调用时注入
                    break;
                case USER:
                    String userContent = ((UserMessage) message).singleText();
                    if (userContent != null) {
                        aiService.saveMessage(chatId, ChatMessage.MessageType.USER, userContent);
                    }
                    break;
                case AI:
                    AiMessage aiMessage = (AiMessage) message;
                    if (aiMessage.hasToolExecutionRequests()) {
                        // 保存工具请求为 TOOL_REQUEST，内容为 JSON 数组
                        String toolReqJson = serializeToolRequests(aiMessage.toolExecutionRequests());
                        aiService.saveMessage(chatId, ChatMessage.MessageType.TOOL_REQUEST, toolReqJson);
                    }
                    String aiContent = aiMessage.text();
                    if (aiContent != null) {
                        aiService.saveMessage(chatId, ChatMessage.MessageType.ASSISTANT, aiContent);
                    }
                    break;
                case TOOL_EXECUTION_RESULT:
                    ToolExecutionResultMessage toolResult = (ToolExecutionResultMessage) message;
                    String resultJson = serializeToolResult(
                            toolResult.id(), toolResult.toolName(), toolResult.text());
                    aiService.saveMessage(chatId, ChatMessage.MessageType.TOOL_RESULT, resultJson);
                    break;
                default:
                    break;
            }
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteMessages(Object memoryId) {
        aiService.deleteMessagesByChatId(memoryId.toString());
    }

    // ---- 工具消息的序列化/反序列化（基于 Jackson，保证 arguments 始终是合法 JSON）----

    @Data
    static class ToolRequestDto {
        private String id;
        private String name;
        private String arguments;
    }

    @Data
    static class ToolResultDto {
        private String id;
        private String toolName;
        private String text;
    }

    private String serializeToolRequests(List<ToolExecutionRequest> requests) {
        try {
            List<ToolRequestDto> dtos = new ArrayList<>();
            for (ToolExecutionRequest req : requests) {
                ToolRequestDto dto = new ToolRequestDto();
                dto.setId(req.id());
                dto.setName(req.name());
                dto.setArguments(normalizeArguments(req.arguments()));
                dtos.add(dto);
            }
            return objectMapper.writeValueAsString(dtos);
        } catch (Exception e) {
            log.error("序列化工具请求失败", e);
            return "[]";
        }
    }

    private List<ToolExecutionRequest> parseToolRequests(String content) {
        try {
            List<ToolRequestDto> dtos = objectMapper.readValue(
                    content, new TypeReference<List<ToolRequestDto>>() {});
            List<ToolExecutionRequest> requests = new ArrayList<>();
            for (ToolRequestDto dto : dtos) {
                requests.add(ToolExecutionRequest.builder()
                        .id(dto.getId())
                        .name(dto.getName())
                        .arguments(normalizeArguments(dto.getArguments()))
                        .build());
            }
            return requests;
        } catch (Exception e) {
            log.warn("解析工具请求失败（旧格式或损坏数据），跳过该消息: {}", content, e);
            return Collections.emptyList();
        }
    }

    private String serializeToolResult(String id, String toolName, String text) {
        try {
            ToolResultDto dto = new ToolResultDto();
            dto.setId(id);
            dto.setToolName(toolName);
            dto.setText(text != null ? text : "");
            return objectMapper.writeValueAsString(dto);
        } catch (Exception e) {
            log.error("序列化工具结果失败", e);
            return "{}";
        }
    }

    private ToolResultDto parseToolResult(String content) {
        try {
            return objectMapper.readValue(content, ToolResultDto.class);
        } catch (Exception e) {
            log.warn("解析工具结果失败（旧格式或损坏数据），跳过该消息: {}", content, e);
            return null;
        }
    }

    /**
     * 规范化 tool arguments 为合法 JSON 对象字符串。
     * 兼容旧数据：早期版本反序列化时丢失了外层花括号（形如 "name": "xxx"），
     * 这里自动补齐为 {"name": "xxx"}；为空时返回 {}。
     */
    private String normalizeArguments(String args) {
        if (args == null) {
            return "{}";
        }
        String trimmed = args.trim();
        if (trimmed.isEmpty()) {
            return "{}";
        }
        if (!trimmed.startsWith("{")) {
            return "{" + trimmed + "}";
        }
        return trimmed;
    }
}