package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.constant.MessageConstant;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Normalizes the Authorization header once so every entry point agrees on what a token is.
 *
 * <p>Legacy clients send the bare JWT; newer clients send the standard
 * {@code Bearer <token>} form. Anything else is rejected rather than silently treated
 * as an anonymous request, because doing the latter would downgrade an invalid session
 * into public access on optional-auth endpoints.
 */
public final class TokenExtractor {

    private static final String BEARER_PREFIX = "Bearer ";

    private TokenExtractor() {
    }

    /**
     * @return the normalized token, or {@code null} when no Authorization header was sent
     * @throws ResponseStatusException 401 when a header is present but malformed
     */
    public static String extract(String authorization) {
        if (!StringUtils.hasText(authorization)) {
            return null;
        }
        String value = authorization.trim();
        if (value.startsWith(BEARER_PREFIX)) {
            String token = value.substring(BEARER_PREFIX.length());
            if (!isValidTokenShape(token)) {
                throw unauthorized();
            }
            return token;
        }
        if (value.regionMatches(true, 0, "Bearer", 0, 6) || value.contains(" ")) {
            throw unauthorized();
        }
        if (!isValidTokenShape(value)) {
            throw unauthorized();
        }
        return value;
    }

    private static boolean isValidTokenShape(String token) {
        return StringUtils.hasText(token)
                && token.equals(token.trim())
                && !token.contains(" ");
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, MessageConstant.SESSION_EXPIRED);
    }

}
