package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.memory.MysqlChatMemoryStore;
import cn.edu.seig.vibemusic.model.entity.ChatMessage;
import cn.edu.seig.vibemusic.service.IAiService;
import cn.edu.seig.vibemusic.service.ai.AiAssistantFactory;
import cn.edu.seig.vibemusic.service.ai.AiRunBudget;
import cn.edu.seig.vibemusic.tool.MusicTools;
import cn.edu.seig.vibemusic.tool.PlaylistActionTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.dashscope.QwenChatModel;
import dev.langchain4j.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用真实 AiServices 编排与消息窗口，持久化和模型用替身，绝不发出付费模型请求。 */
class AiAssistantFactoryTest {
    private final QwenChatModel model = mock(QwenChatModel.class);
    private final QwenStreamingChatModel streaming = mock(QwenStreamingChatModel.class);
    private final IAiService service = mock(IAiService.class);
    private final MysqlChatMemoryStore store = new MysqlChatMemoryStore(service, new ObjectMapper());
    private final MusicTools tools = mock(MusicTools.class);
    private final PlaylistActionTools actionTools = mock(PlaylistActionTools.class);
    private final AiAssistantFactory factory = new AiAssistantFactory(model, streaming, store, tools, actionTools);
    private final Map<String, List<ChatMessage>> history = new ConcurrentHashMap<>();
    private final Map<String, String> contexts = new ConcurrentHashMap<>();
    private final Map<String, String> leases = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(factory, "timeoutSeconds", 10);
        ReflectionTestUtils.setField(factory, "maxToolCalls", 2);
        ReflectionTestUtils.setField(factory, "modelName", "test-model");
        // 工具实例每次运行都会派生一份带上下文的副本；替身直接返回自身即可。
        when(tools.withContext(any())).thenReturn(tools);
        when(actionTools.withContext(any())).thenReturn(actionTools);
        doAnswer(call -> {
            String chat = call.getArgument(0), token = call.getArgument(1);
            if (leases.putIfAbsent(chat, token) != null) throw new ResponseStatusException(HttpStatus.CONFLICT);
            return null;
        }).when(service).acquireConversation(anyString(), anyString(), anyLong());
        doAnswer(call -> { leases.remove(call.getArgument(0), call.getArgument(1)); return null; })
                .when(service).releaseConversation(anyString(), anyString());
        when(service.getModelContext(anyString())).thenAnswer(call -> contexts.get(call.getArgument(0)));
        when(service.getRecentMessages(anyString(), anyInt())).thenAnswer(call -> {
            List<ChatMessage> rows = new ArrayList<>(history.getOrDefault(call.getArgument(0), List.of()));
            Collections.reverse(rows);
            return rows.stream().limit((Integer) call.getArgument(1)).toList();
        });
        doAnswer(call -> {
            String chat = call.getArgument(0), token = call.getArgument(1);
            if (!token.equals(leases.get(chat))) throw new ResponseStatusException(HttpStatus.CONFLICT);
            contexts.put(chat, call.getArgument(2));
            List<ChatMessage> added = call.getArgument(3);
            for (ChatMessage row : added) {
                row.setId(ids.incrementAndGet());
                row.setChatId(chat);
                row.setCreatedAt(LocalDateTime.now());
                history.computeIfAbsent(chat, ignored -> new ArrayList<>()).add(row);
            }
            return null;
        }).when(service).appendConversation(anyString(), anyString(), anyString(), anyList());
        doAnswer(call -> {
            String chat = call.getArgument(0);
            if (leases.containsKey(chat)) throw new ResponseStatusException(HttpStatus.CONFLICT);
            history.remove(chat);
            contexts.put(chat, "[]");
            return null;
        }).when(service).deleteMessagesByChatId(anyString());
    }

    private String chat(String chatId, String query) {
        try (AiAssistantFactory.Run run = factory.create(chatId)) { return run.assistant().chat(chatId, query); }
    }

    private Response<AiMessage> answer(String text) { return Response.from(AiMessage.from(text), new TokenUsage(10, 2)); }

    @Test void actualAiServicesPreservesUserMemoryAcrossSeparateRuns() {
        when(model.generate(anyList(), anyList())).thenReturn(answer("你好"));
        assertEquals("你好", chat("user_chat_id_1", "第一条"));
        assertEquals("你好", chat("user_chat_id_1", "第二条"));
        assertTrue(contexts.get("user_chat_id_1").contains("第一条"));
        chat("user_chat_id_2", "另一个用户");
        assertFalse(contexts.get("user_chat_id_2").contains("第一条"));
        assertTrue(leases.isEmpty());
    }

    @Test void fullHistoryKeepsStableIdsAndTimestampsAfterWindowEviction() {
        when(model.generate(anyList(), anyList())).thenAnswer(call -> {
            assertTrue(((List<?>) call.getArgument(0)).size() <= MysqlChatMemoryStore.WINDOW_SIZE);
            return answer("回复");
        });
        chat("u1", "最早的问题");
        ChatMessage first = history.get("u1").get(0);
        Long firstId = first.getId();
        LocalDateTime firstTime = first.getCreatedAt();
        for (int turn = 2; turn <= 15; turn++) chat("u1", "问题 " + turn);
        assertEquals(30, history.get("u1").size());
        assertEquals(30, history.get("u1").stream().map(ChatMessage::getId).distinct().count());
        assertEquals(firstId, history.get("u1").get(0).getId());
        assertEquals(firstTime, history.get("u1").get(0).getCreatedAt());
        assertFalse(contexts.get("u1").contains("最早的问题"));
        assertTrue(ChatMessageDeserializer.messagesFromJson(contexts.get("u1")).size() <= 20);
        verify(service, never()).deleteMessagesByChatId(anyString());
    }

    @Test void toolAndSystemMessagesAreNotDuplicatedWhenLoadingTheNextRun() {
        ToolExecutionRequest tool = ToolExecutionRequest.builder().id("search").name("searchSongs")
                .arguments("{\"keyword\":\"轻音乐\"}").build();
        when(model.generate(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from(tool), new TokenUsage(10, 2)), answer("找到了"),
                        Response.from(AiMessage.from(tool), new TokenUsage(10, 2)), answer("找到了"));
        when(tools.searchSongs("轻音乐")).thenReturn("歌曲");
        chat("u1", "搜歌");
        chat("u1", "再搜歌");
        List<ChatMessage> rows = history.get("u1");
        assertEquals(8, rows.size());
        for (ChatMessage.MessageType type : List.of(ChatMessage.MessageType.USER, ChatMessage.MessageType.ASSISTANT,
                ChatMessage.MessageType.TOOL_REQUEST, ChatMessage.MessageType.TOOL_RESULT)) {
            assertEquals(2, rows.stream().filter(row -> row.getMessageType() == type).count());
        }
        assertEquals(0, rows.stream().filter(row -> row.getMessageType() == ChatMessage.MessageType.SYSTEM).count());
    }

    @Test void actualToolLoopStopsBeforeExecutingAnExtraTool() {
        ToolExecutionRequest tool = ToolExecutionRequest.builder().id("search").name("searchSongs")
                .arguments("{\"keyword\":\"轻音乐\"}").build();
        when(model.generate(anyList(), anyList())).thenReturn(Response.from(AiMessage.from(tool), new TokenUsage(10, 2)));
        when(tools.searchSongs("轻音乐")).thenReturn("没有找到");
        assertThrows(RuntimeException.class, () -> chat("user_chat_id_1", "搜歌"));
        verify(tools, times(2)).searchSongs("轻音乐");
        assertTrue(leases.isEmpty());
    }

    @Test void sameConversationIsBusyUntilCloseWhileOtherConversationsAreIndependent() {
        when(model.generate(anyList(), anyList())).thenReturn(answer("可以"));
        try (AiAssistantFactory.Run first = factory.create("u1")) {
            assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                    () -> factory.create("u1")).getStatusCode());
            assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                    () -> service.deleteMessagesByChatId("u1")).getStatusCode());
            assertEquals("可以", chat("u2", "另一个会话"));
        }
        assertEquals("可以", chat("u1", "继续"));
        assertTrue(leases.isEmpty());
    }

    @Test @SuppressWarnings("unchecked") void actualStreamingServiceWorksWithThePerRunBudget() throws Exception {
        doAnswer(call -> {
            StreamingResponseHandler<AiMessage> handler = call.getArgument(2);
            handler.onNext("你好");
            handler.onComplete(answer("你好"));
            return null;
        }).when(streaming).generate(anyList(), anyList(), any());
        CompletableFuture<String> result = new CompletableFuture<>();
        try (AiAssistantFactory.Run run = factory.create("guest_chat_v2_test")) {
            run.assistant().chatStream("guest_chat_v2_test", "你好")
                    .onNext(token -> {}).onComplete(response -> result.complete(response.content().text()))
                    .onError(result::completeExceptionally).start();
            assertEquals("你好", result.get(2, TimeUnit.SECONDS));
        }
        assertEquals(2, history.get("guest_chat_v2_test").size());
        assertTrue(leases.isEmpty());
    }

    @Test @SuppressWarnings("unchecked") void lateStreamingCallbackCannotRepopulateClearedHistory() {
        AtomicReference<StreamingResponseHandler<AiMessage>> callback = new AtomicReference<>();
        doAnswer(call -> { callback.set(call.getArgument(2)); return null; })
                .when(streaming).generate(anyList(), anyList(), any());
        AiAssistantFactory.Run run = factory.create("u1");
        run.assistant().chatStream("u1", "旧问题").onNext(token -> {})
                .onComplete(response -> fail("过期响应不能完成")).onError(error -> {}).start();
        assertEquals(1, history.get("u1").size());
        run.close();
        service.deleteMessagesByChatId("u1");
        callback.get().onNext("迟到的片段");
        callback.get().onComplete(answer("迟到的回复"));
        assertFalse(history.containsKey("u1"));
        assertEquals("[]", contexts.get("u1"));
        when(model.generate(anyList(), anyList())).thenAnswer(call -> {
            assertFalse(call.getArgument(0).toString().contains("旧问题"));
            return answer("新回复");
        });
        chat("u1", "新问题");
        assertEquals(2, history.get("u1").size());
    }

    @Test void incompleteToolExchangeRemainsInHistoryButIsNotReplayed() {
        ToolExecutionRequest one = ToolExecutionRequest.builder().id("one").name("searchSongs").arguments("{}").build();
        ToolExecutionRequest two = ToolExecutionRequest.builder().id("two").name("searchSongs").arguments("{}").build();
        try (MysqlChatMemoryStore.Conversation memory = store.open("u1", new AiRunBudget(Duration.ofSeconds(10), 4), Duration.ofSeconds(10))) {
            memory.add(UserMessage.from("问题"));
            memory.add(AiMessage.from(List.of(one, two)));
            memory.add(ToolExecutionResultMessage.from("one", "searchSongs", "部分结果"));
        }
        assertEquals(3, history.get("u1").size());
        try (MysqlChatMemoryStore.Conversation memory = store.open("u1", new AiRunBudget(Duration.ofSeconds(10), 4), Duration.ofSeconds(10))) {
            assertEquals(List.of(UserMessage.from("问题")), memory.messages());
        }
        assertEquals(3, history.get("u1").size());
    }

    @Test void legacyHistoryIsLoadedOnceWithoutReinsertingOrChangingRows() {
        ChatMessage legacy = ChatMessage.builder().id(200L).chatId("u1").messageType(ChatMessage.MessageType.USER)
                .content("旧消息").createdAt(LocalDateTime.of(2020, 1, 1, 1, 1)).build();
        history.put("u1", new ArrayList<>(List.of(legacy)));
        when(model.generate(anyList(), anyList())).thenReturn(answer("你好"));
        chat("u1", "新消息");
        assertSame(legacy, history.get("u1").get(0));
        assertEquals(3, history.get("u1").size());
        chat("u1", "又一条");
        verify(service, times(1)).getRecentMessages("u1", 40);
        assertEquals(5, history.get("u1").size());
    }
}
