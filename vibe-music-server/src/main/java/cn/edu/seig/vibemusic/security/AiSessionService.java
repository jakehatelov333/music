package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.service.agent.ConversationService;
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

/**
 * 「这次请求属于哪一段对话」的唯一判定点。
 *
 * <p><b>身份与会话是两件事，不要混在一起：</b>身份永远来自服务端（JWT 或游客 Cookie），
 * 客户端无从参与；会话则是客户端可以指定、但必须由服务端校验归属的资源。
 * 登录用户交给 {@link ConversationService#resolve} 做归属判定，
 * 游客走 Cookie 映射（见 {@link #resolveGuest}）。
 */
@Service
@RequiredArgsConstructor
public class AiSessionService {
    public static final String COOKIE_NAME = "vibe_guest_session";
    static final String KEY_PREFIX = "ai:guest:v2:";
    private static final Duration TTL = Duration.ofHours(1);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final StringRedisTemplate redis;
    private final ConversationService conversations;

    /** 不指定会话的入口：沿用默认会话（登录用户）或当前游客会话。 */
    public String resolve(HttpServletRequest request, HttpServletResponse response) {
        return resolve(request, response, null, false);
    }

    /**
     * @param chatId          客户端指定的会话；为空表示沿用默认会话
     * @param newConversation true 表示开一段全新对话，此时 {@code chatId} 被忽略
     */
    public String resolve(HttpServletRequest request, HttpServletResponse response, String chatId,
                          boolean newConversation) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        Long userId = CurrentUser.id();
        if (userId != null) return conversations.resolve(userId, chatId, newConversation);
        return resolveGuest(request, response, newConversation);
    }

    /**
     * 游客会话。
     *
     * <p><b>游客只有「当前会话」和「新会话」两种状态。</b>Cookie 到 chatId 的映射在 Redis 里
     * 是一对一且带 1 小时 TTL 的，没有地方存放会话列表，所以游客的多会话范围到此为止 ——
     * 这是第一版刻意的取舍，不是遗漏。
     *
     * <p><b>客户端传来的 chatId 对游客一律不生效。</b>这里选择「忽略」而不是「报错」：
     * 忽略不会造成任何越权，而报错会把「这个 chatId 是否存在」变成可探测的信息。
     */
    private String resolveGuest(HttpServletRequest request, HttpServletResponse response,
                                boolean newConversation) {
        if (!newConversation) {
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
        }

        // 开新对话或凭据已失效：签发新凭据并覆盖 Cookie 上的映射。
        // 旧的游客会话随之不可达 —— 与「游客不做历史列表」的约定一致。
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
