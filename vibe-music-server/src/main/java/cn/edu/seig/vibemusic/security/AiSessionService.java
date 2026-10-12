package cn.edu.seig.vibemusic.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AiSessionService {
    public static final String COOKIE_NAME = "vibe_guest_session";
    static final String KEY_PREFIX = "ai:guest:v2:";
    private static final Duration TTL = Duration.ofHours(1);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final StringRedisTemplate redis;

    public String resolve(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        Long userId = CurrentUser.id();
        if (userId != null) return "user_chat_id_" + userId;

        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (!COOKIE_NAME.equals(cookie.getName())) continue;
                String token = cookie.getValue();
                if (token != null && token.matches("[A-Za-z0-9_-]{43}")) {
                    String chatId = redis.opsForValue().get(KEY_PREFIX + token);
                    if (chatId != null && chatId.startsWith("guest_chat_v2_")) return chatId;
                }
                break;
            }
        }

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String chatId = "guest_chat_v2_" + UUID.randomUUID();
        redis.opsForValue().set(KEY_PREFIX + token, chatId, TTL);
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE_NAME, token)
                .path("/ai").httpOnly(true).secure(request.isSecure()).sameSite("Lax")
                .maxAge(TTL).build().toString());
        return chatId;
    }
}
