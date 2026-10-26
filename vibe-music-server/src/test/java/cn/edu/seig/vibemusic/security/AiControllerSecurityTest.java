package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.config.RolePermissionManager;
import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.controller.AiController;
import cn.edu.seig.vibemusic.handler.GlobalExceptionHandler;
import cn.edu.seig.vibemusic.interceptor.LoginInterceptor;
import cn.edu.seig.vibemusic.interceptor.RateLimitInterceptor;
import cn.edu.seig.vibemusic.service.IAiService;
import cn.edu.seig.vibemusic.service.MusicAssistant;
import cn.edu.seig.vibemusic.service.agent.ChatSummaryService;
import cn.edu.seig.vibemusic.service.agent.MusicTasteSummaryService;
import cn.edu.seig.vibemusic.service.agent.UserMusicProfileService;
import cn.edu.seig.vibemusic.service.ai.AiAssistantFactory;
import cn.edu.seig.vibemusic.service.ai.AiRunBudget;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.service.TokenStream;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiControllerSecurityTest {
    private final IAiService service = mock(IAiService.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final AuthSessionService sessions = mock(AuthSessionService.class);
    private final AiAssistantFactory factory = mock(AiAssistantFactory.class);
    private final RedisRateLimiter limiter = mock(RedisRateLimiter.class);
    private final ChatSummaryService chatSummaries = mock(ChatSummaryService.class);
    private final MusicTasteSummaryService tasteSummaries = mock(MusicTasteSummaryService.class);
    private final UserMusicProfileService musicProfiles = mock(UserMusicProfileService.class);
    /** 归属查询走真实 ConversationService + 打桩 mapper：越权判定本身正是这个测试要验证的东西。 */
    private final cn.edu.seig.vibemusic.mapper.AiMapper aiMapper = mock(cn.edu.seig.vibemusic.mapper.AiMapper.class);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private MockMvc mvc;
    private String token;

    @BeforeEach void setup() {
        when(redis.opsForValue()).thenReturn(mock(org.springframework.data.redis.core.ValueOperations.class));
        token = "valid-user-token";
        Map<String, Object> claims = Map.of(
                JwtClaimsConstant.USER_ID, 7L, JwtClaimsConstant.ROLE, "ROLE_USER");
        when(sessions.authenticate(token)).thenReturn(claims);
        when(factory.timeout()).thenReturn(Duration.ofSeconds(2));
        cn.edu.seig.vibemusic.service.agent.ConversationService conversations =
                new cn.edu.seig.vibemusic.service.agent.ConversationService(aiMapper,
                        mock(cn.edu.seig.vibemusic.mapper.AgentPendingActionMapper.class), service);
        ReflectionTestUtils.setField(conversations, "listLimit", 20);
        LoginInterceptor login = new LoginInterceptor();
        RolePermissionManager roles = mock(RolePermissionManager.class);
        when(roles.hasPermission(eq("ROLE_USER"), org.mockito.ArgumentMatchers.startsWith("/ai/"))).thenReturn(true);
        ReflectionTestUtils.setField(login, "authSessionService", sessions);
        ReflectionTestUtils.setField(login, "rolePermissionManager", roles);
        ReflectionTestUtils.setField(login, "objectMapper", new ObjectMapper());
        mvc = MockMvcBuilders.standaloneSetup(new AiController(service, new AiSessionService(redis, conversations),
                        factory,
                        mock(cn.edu.seig.vibemusic.service.agent.AgentMetricsRecorder.class), executor, timer,
                        chatSummaries, tasteSummaries, musicProfiles, conversations))
                .addInterceptors(login, new RateLimitInterceptor(limiter, new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach void cleanup() { executor.shutdownNow(); timer.shutdownNow(); ThreadLocalUtil.remove(); }

    /**
     * 会话归属：客户端传别人的 chatId 一律 404。
     *
     * <p>这条用例是本次多会话改造最核心的安全契约。改造前 chatId 由服务端按身份推导，
     * 客户端传什么都不生效；改造后 chatId 变成客户端可以指定的资源，
     * 「传了别人的 ID 会怎样」就必须有明确的、被测试钉死的答案：
     * <b>404，而不是静默回退到自己的会话</b> —— 静默回退会让调用方以为操作成功了。
     */
    @Test void userCannotReadOrDeleteAnotherConversationByPassingChatId() throws Exception {
        when(aiMapper.findConversationOwner("user_chat_id_8")).thenReturn(8L);
        mvc.perform(get("/ai/chat/history").header("Authorization", token).param("chatId", "user_chat_id_8"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/ai/chat/clear").header("Authorization", token).param("chatId", "user_chat_id_8"))
                .andExpect(status().isNotFound());
        verify(service, never()).getMessagesByChatId("user_chat_id_8");
        verify(service, never()).deleteMessagesByChatId("user_chat_id_8");
    }

    /** 传自己的非默认会话：正常放行。 */
    @Test void userCanReadTheirOwnNonDefaultConversation() throws Exception {
        when(aiMapper.findConversationOwner("user_chat_id_7_abc")).thenReturn(7L);
        when(service.getMessagesByChatId("user_chat_id_7_abc")).thenReturn(List.of());
        mvc.perform(get("/ai/chat/history").header("Authorization", token).param("chatId", "user_chat_id_7_abc"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(service).getMessagesByChatId("user_chat_id_7_abc");
    }

    @Test void forgedGuestCookieCannotSelectAUserConversation() throws Exception {
        mvc.perform(get("/ai/chat/history").cookie(new Cookie("guest_chat_id", "user_chat_id_7")))
                .andExpect(status().isOk()).andExpect(header().string("Set-Cookie", containsString("HttpOnly")));
        verify(service).getMessagesByChatId(org.mockito.ArgumentMatchers.startsWith("guest_chat_v2_"));
        verify(service, never()).getMessagesByChatId("user_chat_id_7");
    }

    @Test void invalidLoginTokenIsNotDowngradedToGuest() throws Exception {
        doThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "会话已失效"))
                .when(sessions).authenticate("invalid");
        mvc.perform(get("/ai/chat/history").header("Authorization", "invalid"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1));
        verifyNoInteractions(service);
    }

    @Test void blankAndOversizedMessagesNeverReachTheModel() throws Exception {
        for (String message : List.of(" ", "x".repeat(2001))) {
            mvc.perform(post("/ai/chat").contentType(MediaType.APPLICATION_JSON)
                            .content(new ObjectMapper().writeValueAsString(Map.of("query", message))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1));
        }
        verifyNoInteractions(factory);
    }

    @Test void negativeLimitAndOversizedPageAreRejected() throws Exception {
        mvc.perform(get("/ai/chat/recent").param("limit", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/ai/chat/history/page").param("size", "101")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void rateLimitRejectsBeforeCallingTheModel() throws Exception {
        when(limiter.retryAfter("ai-generation", "ip:127.0.0.1", 5, 60)).thenReturn(15L);
        mvc.perform(post("/ai/chat/stream").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"hello\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "15"));
        verifyNoInteractions(factory);
    }

    @Test void databaseErrorsDoNotExposeSqlOrUserContent() throws Exception {
        when(service.getMessagesByChatId(anyString())).thenThrow(new DataIntegrityViolationException("secret SQL private-content"));
        mvc.perform(get("/ai/chat/history")).andExpect(status().isConflict())
                .andExpect(content().string(not(containsString("private-content"))))
                .andExpect(content().string(not(containsString("SQL"))));
    }

    @Test void unexpectedFailuresAreSanitized() throws Exception {
        when(service.getMessagesByChatId(anyString())).thenThrow(new IllegalStateException("api-key-secret"));
        mvc.perform(get("/ai/chat/history")).andExpect(status().isInternalServerError())
                .andExpect(content().string(not(containsString("api-key-secret"))))
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test void blockingTaskTimesOutAndClosesItsBudget() throws Exception {
        MusicAssistant assistant = mock(MusicAssistant.class);
        AiRunBudget budget = new AiRunBudget(Duration.ofSeconds(2), 4);
        when(factory.timeout()).thenReturn(Duration.ofMillis(50));
        when(factory.create(anyString(), any(), any())).thenReturn(new AiAssistantFactory.Run(assistant, budget));
        when(assistant.chat(anyString(), anyString())).thenAnswer(call -> {
            new CountDownLatch(1).await();
            return "unreachable";
        });
        mvc.perform(post("/ai/chat").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"hello\"}"))
                .andExpect(status().isGatewayTimeout());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, budget::check);
    }

    @Test void silentStreamTimesOutWithATerminalEvent() throws Exception {
        MusicAssistant assistant = mock(MusicAssistant.class);
        TokenStream stream = mock(TokenStream.class, RETURNS_SELF);
        AiRunBudget budget = new AiRunBudget(Duration.ofSeconds(2), 4);
        when(factory.timeout()).thenReturn(Duration.ofMillis(50));
        when(factory.create(anyString(), any(), any())).thenReturn(new AiAssistantFactory.Run(assistant, budget));
        when(assistant.chatStream(anyString(), anyString())).thenReturn(stream);
        MvcResult result = mvc.perform(post("/ai/chat/stream").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"hello\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().string(containsString("event:error")));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, budget::check);
    }

    @Test @SuppressWarnings("unchecked") void streamingProviderErrorsAreSanitized() throws Exception {
        MusicAssistant assistant = mock(MusicAssistant.class);
        TokenStream stream = mock(TokenStream.class, RETURNS_SELF);
        AtomicReference<Consumer<Throwable>> error = new AtomicReference<>();
        when(factory.create(anyString(), any(), any())).thenReturn(new AiAssistantFactory.Run(assistant, new AiRunBudget(Duration.ofSeconds(2), 4)));
        when(assistant.chatStream(anyString(), anyString())).thenReturn(stream);
        doAnswer(call -> { error.set(call.getArgument(0)); return stream; }).when(stream).onError(any());
        doAnswer(call -> { error.get().accept(new IllegalStateException("api-key-secret")); return null; }).when(stream).start();
        MvcResult result = mvc.perform(post("/ai/chat/stream").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"hello\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().string(containsString("event:error")))
                .andExpect(content().string(not(containsString("api-key-secret"))));
    }

    /** 画像是用户数据，不对匿名开放。 */
    @Test void profileEndpointRequiresLogin() throws Exception {
        mvc.perform(get("/ai/profile")).andExpect(status().isUnauthorized());
        verifyNoInteractions(musicProfiles);
    }

    /** 只读且只返回自己的画像。 */
    @Test void profileEndpointReturnsOnlyOwnProfile() throws Exception {
        var profile = new cn.edu.seig.vibemusic.model.entity.UserMusicProfile();
        profile.setUserId(7L);
        profile.setFavSongCount(3);
        profile.setPrefSummary("偏爱流行与民谣。");
        when(musicProfiles.read(7L)).thenReturn(profile);

        mvc.perform(get("/ai/profile").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(7))
                .andExpect(jsonPath("$.favSongCount").value(3))
                .andExpect(jsonPath("$.prefSummary").value("偏爱流行与民谣。"));
        verify(musicProfiles).read(7L);
    }
}
