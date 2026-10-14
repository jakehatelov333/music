package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiSessionServiceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final cn.edu.seig.vibemusic.service.agent.ConversationService conversations =
            mock(cn.edu.seig.vibemusic.service.agent.ConversationService.class);
    private final AiSessionService service = new AiSessionService(redis, conversations);
    AiSessionServiceTest() { when(redis.opsForValue()).thenReturn(values); }
    @AfterEach void cleanup() { ThreadLocalUtil.remove(); }

    @Test void forgedInternalIdCannotReadAUsersConversation() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AiSessionService.COOKIE_NAME, "user_chat_id_12"),
                new Cookie("guest_chat_id", "user_chat_id_12"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        String id = service.resolve(request, response);
        assertTrue(id.startsWith("guest_chat_v2_"));
        assertNotEquals("user_chat_id_12", id);
        verify(values, never()).get(anyString());
        verify(values).set(matches("ai:guest:v2:[A-Za-z0-9_-]{43}"), eq(id), eq(Duration.ofHours(1)));
        String cookie = response.getHeader("Set-Cookie");
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Lax"));
        assertTrue(cookie.contains("Max-Age=3600"));
        assertTrue(cookie.contains("Path=/ai"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }

    @Test void validGuestCredentialReusesItsRegisteredConversation() {
        String token = "a".repeat(43);
        when(values.get(AiSessionService.KEY_PREFIX + token)).thenReturn("guest_chat_v2_existing");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AiSessionService.COOKIE_NAME, token));
        assertEquals("guest_chat_v2_existing", service.resolve(request, new MockHttpServletResponse()));
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test void expiredCredentialStartsANewConversation() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AiSessionService.COOKIE_NAME, "a".repeat(43)));
        assertTrue(service.resolve(request, new MockHttpServletResponse()).startsWith("guest_chat_v2_"));
        verify(values).set(anyString(), anyString(), eq(Duration.ofHours(1)));
    }

    @Test void guestRegistryCannotResolveToAUserNamespace() {
        String token = "a".repeat(43);
        when(values.get(AiSessionService.KEY_PREFIX + token)).thenReturn("user_chat_id_12");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AiSessionService.COOKIE_NAME, token));
        assertTrue(service.resolve(request, new MockHttpServletResponse()).startsWith("guest_chat_v2_"));
    }

    /**
     * 登录用户的会话完全交给 ConversationService 判定归属，游客 Cookie 一概不参与。
     *
     * <p>改造前这条用例断言的是「请求里的 chatId 被忽略」—— 因为那时 chatId 由服务端按身份推导。
     * 现在 chatId 是客户端可以指定的资源，服务端的职责从「忽略它」变成「校验它」，
     * 因此这里断言的是「原样交给 ConversationService」，越权与否由那条链路负责（见
     * {@code AiControllerSecurityTest#userCannotReadOrDeleteAnotherConversationByPassingChatId}）。
     */
    @Test void authenticatedUserDelegatesConversationChoiceToOwnershipCheck() {
        ThreadLocalUtil.set(Map.of(JwtClaimsConstant.USER_ID, 42L));
        when(conversations.resolve(42L, "user_chat_id_12", false)).thenReturn("user_chat_id_42");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AiSessionService.COOKIE_NAME, "a".repeat(43)));
        request.setParameter("chatId", "user_chat_id_12");
        assertEquals("user_chat_id_42",
                service.resolve(request, new MockHttpServletResponse(), "user_chat_id_12", false));
        verify(conversations).resolve(42L, "user_chat_id_12", false);
        verifyNoInteractions(values);
    }

    /** 「新对话」标志原样下传，由 ConversationService 生成新的会话标识。 */
    @Test void newConversationFlagIsForwardedForAuthenticatedUsers() {
        ThreadLocalUtil.set(Map.of(JwtClaimsConstant.USER_ID, 42L));
        when(conversations.resolve(42L, null, true)).thenReturn("user_chat_id_42_fresh");
        assertEquals("user_chat_id_42_fresh",
                service.resolve(new MockHttpServletRequest(), new MockHttpServletResponse(), null, true));
        verify(conversations).resolve(42L, null, true);
        verifyNoInteractions(values);
    }

    /**
     * 游客「新对话」必须签发全新凭据，且不读旧映射 —— 旧会话随之不可达。
     * 这是「游客只有当前会话 + 新会话」这一约定的落地点。
     */
    @Test void guestNewConversationMintsAFreshCredentialWithoutReusingTheOldMapping() {
        String token = "a".repeat(43);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AiSessionService.COOKIE_NAME, token));
        MockHttpServletResponse response = new MockHttpServletResponse();
        String chatId = service.resolve(request, response, null, true);
        assertTrue(chatId.startsWith("guest_chat_v2_"));
        verify(values, never()).get(anyString());
        verify(values).set(matches("ai:guest:v2:[A-Za-z0-9_-]{43}"), eq(chatId), eq(Duration.ofHours(1)));
    }

    /** 游客传 chatId 不生效：不会被当成身份凭证，也不会因此报错暴露 ID 是否存在。 */
    @Test void guestSuppliedChatIdIsIgnoredRatherThanHonoured() {
        String token = "a".repeat(43);
        when(values.get(AiSessionService.KEY_PREFIX + token)).thenReturn("guest_chat_v2_mine");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AiSessionService.COOKIE_NAME, token));
        assertEquals("guest_chat_v2_mine",
                service.resolve(request, new MockHttpServletResponse(), "user_chat_id_12", false));
    }

    @Test void newGuestCredentialsAreDistinctAndSecureOnHttps() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(true);
        MockHttpServletResponse a = new MockHttpServletResponse();
        MockHttpServletResponse b = new MockHttpServletResponse();
        assertNotEquals(service.resolve(request, a), service.resolve(request, b));
        assertNotEquals(a.getHeader("Set-Cookie"), b.getHeader("Set-Cookie"));
        assertTrue(a.getHeader("Set-Cookie").contains("Secure"));
    }
}
