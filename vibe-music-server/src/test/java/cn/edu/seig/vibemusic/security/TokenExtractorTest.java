package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.constant.MessageConstant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokenExtractorTest {

    @Test
    @DisplayName("Bearer 前缀被规范化，裸 JWT 继续兼容")
    void normalizesBearerAndBareToken() {
        assertEquals("abc", TokenExtractor.extract("Bearer abc"));
        assertEquals("abc", TokenExtractor.extract("  abc  "));
        assertNull(TokenExtractor.extract(" "));
    }

    @Test
    @DisplayName("畸形 Authorization 头不得降级为匿名请求")
    void malformedHeaderIsRejected() {
        for (String header : new String[]{
                "Bearer", "bearer abc", "Bearer ", "Bearer  abc",
                "Token abc", "abc def", "Bearer abc def"
        }) {
            ResponseStatusException error = assertThrows(ResponseStatusException.class,
                    () -> TokenExtractor.extract(header), "应拒绝: " + header);
            assertEquals(401, error.getStatusCode().value());
            assertEquals(MessageConstant.SESSION_EXPIRED, error.getReason());
        }
    }
}
