package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.interceptor.RateLimitInterceptor;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RateLimitInterceptorTest {
    private final RedisRateLimiter limiter = mock(RedisRateLimiter.class);
    private final RateLimitInterceptor interceptor = new RateLimitInterceptor(limiter, new ObjectMapper());
    @AfterEach void cleanup() { ThreadLocalUtil.remove(); }

    @Test void guestCannotAvoidGenerationLimitByRotatingCookiesOrForwardedHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ai/chat/stream");
        request.setRemoteAddr("192.0.2.1");
        request.addHeader("X-Forwarded-For", "192.0.2.99");
        when(limiter.retryAfter("ai-generation", "ip:192.0.2.1", 5, 60)).thenReturn(23L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(429, response.getStatus());
        assertEquals("23", response.getHeader("Retry-After"));
        assertEquals(1, new ObjectMapper().readTree(response.getContentAsString()).get("code").asInt());
    }

    @Test void blockingAndStreamingUseTheSameAuthenticatedBudget() throws Exception {
        ThreadLocalUtil.set(Map.of(JwtClaimsConstant.USER_ID, 7L));
        for (String path : new String[]{"/ai/chat", "/ai/chat/stream"}) {
            assertTrue(interceptor.preHandle(new MockHttpServletRequest("POST", path), new MockHttpServletResponse(), new Object()));
        }
        verify(limiter, times(2)).retryAfter("ai-generation", "user:7", 10, 60);
        verify(limiter, times(2)).retryAfter("ai-daily", "user:7", 100, 86400);
    }

    @Test void dailyQuotaIsEnforced() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ai/chat");
        when(limiter.retryAfter("ai-daily", "ip:127.0.0.1", 20, 86400)).thenReturn(3600L);
        assertFalse(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @Test void loginAndEmailHaveIndependentLimits() throws Exception {
        interceptor.preHandle(new MockHttpServletRequest("POST", "/user/login"), new MockHttpServletResponse(), new Object());
        MockHttpServletRequest email = new MockHttpServletRequest("GET", "/user/sendVerificationCode");
        email.setParameter("email", " Test@Example.com ");
        interceptor.preHandle(email, new MockHttpServletResponse(), new Object());
        verify(limiter).retryAfter("login", "127.0.0.1", 20, 300);
        verify(limiter).retryAfter("email-recipient", "test@example.com", 5, 3600);
    }

    @Test void preflightAndAsyncRedispatchDoNotConsumeQuota() throws Exception {
        interceptor.preHandle(new MockHttpServletRequest("OPTIONS", "/ai/chat"), new MockHttpServletResponse(), new Object());
        MockHttpServletRequest async = new MockHttpServletRequest("POST", "/ai/chat/stream");
        async.setDispatcherType(DispatcherType.ASYNC);
        interceptor.preHandle(async, new MockHttpServletResponse(), new Object());
        verifyNoInteractions(limiter);
    }

    @Test void alternateUrlSpellingStillUsesTheMatchedEndpointBudget() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ai/chat;v=1");
        request.setAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/ai/chat");
        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
        verify(limiter).retryAfter("ai-generation", "ip:127.0.0.1", 5, 60);
    }
}
