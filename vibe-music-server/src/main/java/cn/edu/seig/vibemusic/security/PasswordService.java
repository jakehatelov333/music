package cn.edu.seig.vibemusic.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Password hashing with transparent legacy-MD5 migration.
 */
@Service
public class PasswordService {

    private static final int BCRYPT_STRENGTH = 12;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    private final String dummyHash = encoder.encode("dummy-password-not-used");

    public String encode(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /**
     * @return {@code true} for a BCrypt hash or a legacy 32-hex MD5 digest
     */
    public boolean matches(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null || storedHash.isBlank()) {
            dummyMatch(rawPassword);
            return false;
        }
        if (isBcrypt(storedHash)) {
            try {
                return encoder.matches(rawPassword, storedHash);
            } catch (IllegalArgumentException e) {
                dummyMatch(rawPassword);
                return false;
            }
        }
        if (!isLegacyMd5(storedHash)) {
            dummyMatch(rawPassword);
            return false;
        }
        byte[] actual = md5(rawPassword);
        byte[] expected = storedHash.toLowerCase().getBytes(StandardCharsets.US_ASCII);
        boolean matches = MessageDigest.isEqual(actual, expected);
        dummyMatch(rawPassword);
        return matches;
    }

    public boolean needsUpgrade(String storedHash) {
        return isLegacyMd5(storedHash);
    }

    /** Equal-cost work for unknown accounts and disabled accounts. */
    public void dummyMatch(String rawPassword) {
        encoder.matches(rawPassword == null ? "" : rawPassword, dummyHash);
    }

    private static boolean isBcrypt(String value) {
        return value.startsWith("$2a$") || value.startsWith("$2b$") || value.startsWith("$2y$");
    }

    private static boolean isLegacyMd5(String value) {
        return value.matches("(?i)^[0-9a-f]{32}$");
    }

    private static byte[] md5(String rawPassword) {
        return DigestUtils.md5DigestAsHex(rawPassword.getBytes(StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.US_ASCII);
    }
}
