package cn.edu.seig.vibemusic.memory;

import cn.edu.seig.vibemusic.model.entity.ChatMessage;
import cn.edu.seig.vibemusic.service.IAiService;
import cn.edu.seig.vibemusic.service.ai.AiRunBudget;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * chat_messages 是追加历史；ai_chat_state 是独立模型窗口。
 * 每次生成只保留一个最多 20 条的内存窗口，无跨会话 JVM 缓存。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MysqlChatMemoryStore {
    public static final int WINDOW_SIZE = 20;
    private final IAiService aiService;
    private final ObjectMapper objectMapper;

    public Conversation open(String chatId, AiRunBudget budget, Duration timeout) {
        String token = UUID.randomUUID().toString();
        // 使用数据库时间判断租约，进程崩溃后自动可恢复；预留 2 秒给请求收尾。
        long leaseSeconds = Math.max(1, (timeout.toMillis() + 999) / 1000) + 2;
        aiService.acquireConversation(chatId, token, leaseSeconds);
        try {
            String json = aiService.getModelContext(chatId);
            List<dev.langchain4j.data.message.ChatMessage> initial = json == null
                    ? loadLegacyWindow(chatId) : ChatMessageDeserializer.messagesFromJson(json);
            return new Conversation(chatId, token, budget, replayable(initial));
        } catch (RuntimeException error) {
            releaseQuietly(chatId, token);
            throw error;
        }
    }

    public final class Conversation implements ChatMemory, AutoCloseable {
        private final String chatId;
        private final String token;
        private final AiRunBudget budget;
        private final MessageWindowChatMemory window;
        private boolean closed;

        private Conversation(String chatId, String token, AiRunBudget budget,
                             List<dev.langchain4j.data.message.ChatMessage> initial) {
            this.chatId = chatId;
            this.token = token;
            this.budget = budget;
            this.window = MessageWindowChatMemory.builder().id(chatId).maxMessages(WINDOW_SIZE).build();
            // 初始化只加载模型窗口，不经过 add 的历史追加路径。
            initial.forEach(window::add);
        }

        @Override public Object id() { return chatId; }

        @Override public synchronized void add(dev.langchain4j.data.message.ChatMessage message) {
            checkOpen();
            window.add(message);
            // LC 每次注入系统提示词；不能反复写进历史，也不持久化提示词。
            if (message.type() == ChatMessageType.SYSTEM) return;
            List<dev.langchain4j.data.message.ChatMessage> context = window.messages().stream()
                    .filter(value -> value.type() != ChatMessageType.SYSTEM).toList();
            aiService.appendConversation(chatId, token, ChatMessageSerializer.messagesToJson(context), encode(message));
        }

        @Override public synchronized List<dev.langchain4j.data.message.ChatMessage> messages() {
            checkOpen();
            return window.messages();
        }

        @Override public void clear() {
            // 用户清空必须走服务的事务/租约校验，不能让 LC 清窗口时误删全量历史。
            throw new UnsupportedOperationException("请使用会话清空接口");
        }

        private void checkOpen() {
            budget.check();
            if (closed) throw new IllegalStateException("会话请求已结束");
        }

        @Override public void close() {
            budget.close();
            synchronized (this) {
                if (closed) return;
                closed = true;
                // 与 add 同步；close 返回后，该运行不再持有可写的内存窗口。
                releaseQuietly(chatId, token);
            }
        }
    }

    private void releaseQuietly(String chatId, String token) {
        try { aiService.releaseConversation(chatId, token); }
        catch (RuntimeException e) {
            // 即使数据库短暂失联，租约也会过期；不要用释放失败覆盖原始响应。
            log.warn("会话租约释放失败，将等待到期，类型={}", e.getClass().getSimpleName());
        }
    }

    private List<ChatMessage> encode(dev.langchain4j.data.message.ChatMessage message) {
        List<ChatMessage> rows = new ArrayList<>();
        if (message instanceof UserMessage user) {
            rows.add(row(ChatMessage.MessageType.USER, user.singleText()));
        } else if (message instanceof AiMessage ai) {
            if (ai.hasToolExecutionRequests()) {
                List<ToolRequestDto> requests = ai.toolExecutionRequests().stream().map(request -> {
                    ToolRequestDto dto = new ToolRequestDto();
                    dto.setId(request.id());
                    dto.setName(request.name());
                    dto.setArguments(request.arguments());
                    return dto;
                }).toList();
                rows.add(row(ChatMessage.MessageType.TOOL_REQUEST, json(requests)));
            }
            if (ai.text() != null) rows.add(row(ChatMessage.MessageType.ASSISTANT, ai.text()));
        } else if (message instanceof ToolExecutionResultMessage result) {
            ToolResultDto dto = new ToolResultDto();
            dto.setId(result.id());
            dto.setToolName(result.toolName());
            dto.setText(result.text());
            rows.add(row(ChatMessage.MessageType.TOOL_RESULT, json(dto)));
        }
        return rows;
    }

    private ChatMessage row(ChatMessage.MessageType type, String content) {
        return ChatMessage.builder().messageType(type).content(content).build();
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("工具消息序列化失败"); }
    }

    private List<dev.langchain4j.data.message.ChatMessage> loadLegacyWindow(String chatId) {
        // 旧版的 AI 工具请求/文本可能拆成两行，故取最多两倍窗口大小，绝不扫描全量历史。
        List<ChatMessage> rows = new ArrayList<>(aiService.getRecentMessages(chatId, WINDOW_SIZE * 2));
        Collections.reverse(rows);
        List<dev.langchain4j.data.message.ChatMessage> result = new ArrayList<>();
        for (ChatMessage row : rows) {
            try {
                switch (row.getMessageType()) {
                    case USER -> result.add(UserMessage.from(row.getContent()));
                    case ASSISTANT -> result.add(AiMessage.from(row.getContent()));
                    case TOOL_REQUEST -> {
                        List<ToolRequestDto> dtos = objectMapper.readValue(row.getContent(), new TypeReference<>() {});
                        List<ToolExecutionRequest> requests = new ArrayList<>();
                        for (ToolRequestDto dto : dtos) {
                            String args = dto.getArguments();
                            if (args == null || args.isBlank()) args = "{}";
                            else if (!args.trim().startsWith("{")) args = "{" + args + "}";
                            if (!objectMapper.readTree(args).isObject()) throw new IllegalArgumentException("无效工具参数");
                            requests.add(ToolExecutionRequest.builder().id(dto.getId()).name(dto.getName()).arguments(args).build());
                        }
                        if (!requests.isEmpty()) result.add(AiMessage.from(requests));
                    }
                    case TOOL_RESULT -> {
                        ToolResultDto dto = objectMapper.readValue(row.getContent(), ToolResultDto.class);
                        result.add(ToolExecutionResultMessage.from(dto.getId(), dto.getToolName(), dto.getText()));
                    }
                    case SYSTEM -> { }
                }
            } catch (RuntimeException | java.io.IOException e) {
                log.warn("跳过无法回放的旧消息，id={}, 类型={}", row.getId(), e.getClass().getSimpleName());
            }
        }
        return result;
    }

    /** 失败/超时可能留下半段工具交换；历史保留原样，下一次模型请求只回放完整交换。 */
    static List<dev.langchain4j.data.message.ChatMessage> replayable(List<dev.langchain4j.data.message.ChatMessage> messages) {
        List<dev.langchain4j.data.message.ChatMessage> result = new ArrayList<>();
        for (int index = 0; index < messages.size(); index++) {
            dev.langchain4j.data.message.ChatMessage message = messages.get(index);
            if (message instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                Set<String> expected = new HashSet<>();
                ai.toolExecutionRequests().forEach(request -> expected.add(request.id()));
                List<dev.langchain4j.data.message.ChatMessage> exchange = new ArrayList<>();
                exchange.add(message);
                boolean valid = expected.size() == ai.toolExecutionRequests().size() && !expected.contains(null);
                while (index + 1 < messages.size() && messages.get(index + 1) instanceof ToolExecutionResultMessage tool) {
                    index++;
                    if (!expected.remove(tool.id())) valid = false;
                    exchange.add(tool);
                }
                if (valid && expected.isEmpty()) result.addAll(exchange);
            } else if (message.type() != ChatMessageType.TOOL_EXECUTION_RESULT && message.type() != ChatMessageType.SYSTEM) {
                result.add(message);
            }
        }
        return result;
    }

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
}
