package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.constant.MessageConstant;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

/**
 * Single JWT authority. The signing key never falls back to a source literal.
 */
@Slf4j
@Service
public class JwtService {

    private static final String CLAIMS = "claims";

    private final Algorithm algorithm;
    private final JWTVerifier verifier;
    private final Duration ttl;

    public JwtService(@Value("${app.jwt.secret:}") String configuredSecret,
                      @Value("${app.jwt.ttl-hours:6}") long ttlHours,
                      Environment environment) {
        String secret = StringUtils.hasText(configuredSecret) ? configuredSecret.trim() : null;
        if (secret == null || isPlaceholder(secret)) {
            if (isProduction(environment)) {
                throw new IllegalStateException("JWT_SECRET 未配置，生产环境拒绝启动");
            }
            byte[] random = new byte[48];
            new SecureRandom().nextBytes(random);
            secret = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            log.warn("JWT_SECRET 未配置：已为本次本地进程生成临时密钥，重启后既有会话将失效");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET 至少需要 32 字节");
        }
        this.algorithm = Algorithm.HMAC256(secret);
        this.verifier = JWT.require(algorithm).build();
        long hours = Math.max(1, Math.min(ttlHours, 168));
        this.ttl = Duration.ofHours(hours);
    }

    public String generateToken(Map<String, Object> claims) {
        Instant now = Instant.now();
        return JWT.create()
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(now.plus(ttl)))
                .withClaim(CLAIMS, claims)
                .sign(algorithm);
    }

    /**
     * Verifies signature and expiry. Used by protected requests and optional-auth public requests.
     */
    public Map<String, Object> parseToken(String token) {
        return parse(token, verifier);
    }

    /**
     * Verifies the signature but permits an already-expired token. Logout needs this so a
     * correctly signed token can still revoke its server-side session idempotently.
     */
    public Map<String, Object> parseTokenAllowingExpired(String token) {
        try {
            DecodedJWT decoded = JWT.decode(token);
            algorithm.verify(decoded);
            return claims(decoded);
        } catch (JWTVerificationException e) {
            throw new ResponseStatusExceptionFactory().unauthorized();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusExceptionFactory().unauthorized();
        }
    }

    public Duration ttl() {
        return ttl;
    }

    private Map<String, Object> parse(String token, JWTVerifier jwtVerifier) {
        try {
            DecodedJWT decoded = jwtVerifier.verify(token);
            return claims(decoded);
        } catch (TokenExpiredException e) {
            throw new ResponseStatusExceptionFactory().unauthorized();
        } catch (JWTVerificationException | IllegalArgumentException e) {
            throw new ResponseStatusExceptionFactory().unauthorized();
        }
    }

    private Map<String, Object> claims(DecodedJWT decoded) {
        Map<String, Object> claims = decoded.getClaim(CLAIMS).asMap();
        if (claims == null || claims.isEmpty()) {
            throw new ResponseStatusExceptionFactory().unauthorized();
        }
        return claims;
    }

    private static boolean isPlaceholder(String value) {
        return value.startsWith("${") && value.endsWith("}");
    }

    private static boolean isProduction(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    /** Keeps the exception construction in one place without exposing token contents. */
    private static final class ResponseStatusExceptionFactory {
        private org.springframework.web.server.ResponseStatusException unauthorized() {
            return new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, MessageConstant.SESSION_EXPIRED);
        }
    }
}
