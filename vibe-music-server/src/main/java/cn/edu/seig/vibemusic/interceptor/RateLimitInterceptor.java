package cn.edu.seig.vibemusic.interceptor;

import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.security.CurrentUser;
import cn.edu.seig.vibemusic.security.RedisRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import java.util.Locale;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {
    private static final Set<String> LOGIN = Set.of("/user/login", "/admin/login");
    private static final Set<String> ACCOUNT = Set.of("/user/register", "/admin/register", "/user/resetUserPassword");
    private final RedisRateLimiter limiter;
    private final ObjectMapper mapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (request.getDispatcherType() == DispatcherType.ASYNC || "OPTIONS".equals(request.getMethod())) return true;
        Object matchedPath = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String path = matchedPath == null ? request.getRequestURI().substring(request.getContextPath().length())
                : matchedPath.toString();
        // 仅信任容器解析的对端地址，不能直接使用客户端提供的 X-Forwarded-For。
        String ip = request.getRemoteAddr();
        long retry = 0;
        if (LOGIN.contains(path)) retry = limiter.retryAfter("login", ip, 20, 300);
        else if (ACCOUNT.contains(path)) retry = limiter.retryAfter("account", ip, 5, 600);
        else if ("/user/sendVerificationCode".equals(path)) {
            retry = limiter.retryAfter("email-ip", ip, 3, 60);
            String email = request.getParameter("email");
            if (retry == 0 && email != null) retry = limiter.retryAfter("email-recipient",
                    email.strip().toLowerCase(Locale.ROOT), 5, 3600);
        } else if (path.startsWith("/ai/")) {
            retry = limiter.retryAfter("ai-http", ip, 60, 60);
            if (retry == 0 && "POST".equals(request.getMethod())
                    && ("/ai/chat".equals(path) || "/ai/chat/stream".equals(path))) {
                Long userId = CurrentUser.id();
                String principal = userId == null ? "ip:" + ip : "user:" + userId;
                retry = limiter.retryAfter("ai-generation", principal, userId == null ? 5 : 10, 60);
                if (retry == 0) retry = limiter.retryAfter("ai-daily", principal, userId == null ? 20 : 100, 86400);
            }
        }
        if (retry == 0) return true;
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retry));
        response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getWriter(), Result.error("请求过于频繁，请稍后再试"));
        return false;
    }
}
