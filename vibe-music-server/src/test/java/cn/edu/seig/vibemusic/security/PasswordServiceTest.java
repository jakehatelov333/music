package cn.edu.seig.vibemusic.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordServiceTest {

    private final PasswordService service = new PasswordService();

    @Test
    @DisplayName("BCrypt：同一密码两次哈希不同，但都能验证通过")
    void bcryptUsesRandomSaltAndVerifies() {
        String first = service.encode("abc12345");
        String second = service.encode("abc12345");

        assertTrue(first.startsWith("$2"));
        assertNotEquals(first, second);
        assertTrue(service.matches("abc12345", first));
        assertTrue(service.matches("abc12345", second));
        assertFalse(service.matches("wrong123", first));
    }

    @Test
    @DisplayName("旧 MD5：仍可验证，并明确标记需要升级")
    void legacyMd5StillVerifiesAndNeedsUpgrade() {
        String md5 = DigestUtils.md5DigestAsHex(
                "legacy123".getBytes(StandardCharsets.UTF_8));

        assertTrue(service.matches("legacy123", md5));
        assertTrue(service.matches("legacy123", md5.toUpperCase()));
        assertTrue(service.needsUpgrade(md5));
        assertFalse(service.needsUpgrade(service.encode("abc12345")));
    }

    @Test
    @DisplayName("空值、非法哈希、畸形 BCrypt：一律拒绝")
    void malformedHashesAreRejected() {
        assertFalse(service.matches("abc12345", null));
        assertFalse(service.matches("abc12345", ""));
        assertFalse(service.matches("abc12345", "not-a-hash"));
        assertFalse(service.matches("abc12345", "$2a$12$broken"));
        assertFalse(service.matches(null, "not-a-hash"));
    }
}
