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
    private final AiSessionService service = new AiSessionService(redis);
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

    @Test void authenticatedUserIgnoresGuestCookieAndRequestChatId() {
        ThreadLocalUtil.set(Map.of(JwtClaimsConstant.USER_ID, 42L));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AiSessionService.COOKIE_NAME, "a".repeat(43)));
        request.setParameter("chatId", "user_chat_id_12");
        assertEquals("user_chat_id_42", service.resolve(request, new MockHttpServletResponse()));
        verifyNoInteractions(values);
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
