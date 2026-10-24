package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.service.agent.trace.TraceRedactor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 脱敏规则的单元验证。
 *
 * <p>这些用例的存在理由：Trace 会把提示词、消息与工具参数写进数据库。
 * 只要有一条凭据没被拦住，它就永久留在库里（以及备份里），事后展示层再遮盖没有意义。
 * 因此这里逐个断言真实会出现的凭据形态。
 */
class AiTraceRedactionTest {

    private final TraceRedactor redactor = new TraceRedactor();

    @Test
    @DisplayName("Authorization / Bearer 令牌被替换，且不残留原文")
    void bearerTokensAreRedacted() {
        String input = "请求头 Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature";
        TraceRedactor.Result result = redactor.redact(input);
        assertTrue(result.redacted());
        assertFalse(result.text().contains("eyJhbGciOiJIUzI1NiJ9"));
        assertTrue(result.text().contains("***"));
    }

    @Test
    @DisplayName("DashScope 风格 sk- 密钥被替换")
    void apiKeysAreRedacted() {
        TraceRedactor.Result result = redactor.redact("apiKey=sk-abcdefghijklmnopqrstuvwxyz123456");
        assertTrue(result.redacted());
        assertFalse(result.text().contains("abcdefghijklmnopqrstuvwxyz"));
    }

    @Test
    @DisplayName("JSON 里的敏感字段：键保留、值替换，便于看出这里确实带过凭据")
    void sensitiveJsonFieldsKeepKeyButHideValue() {
        String input = "{\"username\":\"alice\",\"password\":\"p@ssw0rd!\",\"accessToken\":\"abc123def456\"}";
        TraceRedactor.Result result = redactor.redact(input);
        assertTrue(result.redacted());
        assertFalse(result.text().contains("p@ssw0rd!"));
        assertFalse(result.text().contains("abc123def456"));
        assertTrue(result.text().contains("\"username\":\"alice\""), "非敏感字段必须原样保留，否则失去排障价值");
        assertTrue(result.text().contains("password"), "键名保留，能看出这里曾有凭据");
    }

    @Test
    @DisplayName("邮箱与手机号不脱敏：它们是业务身份，不是凭据")
    void businessIdentityIsNotRedacted() {
        String input = "用户 alice@example.com 手机号 13800138000 反馈了问题";
        TraceRedactor.Result result = redactor.redact(input);
        assertFalse(result.redacted());
        assertEquals(input, result.text());
    }

    @Test
    @DisplayName("普通对话内容不受影响")
    void ordinaryContentIsUntouched() {
        String input = "帮我建一个夜间驾驶的歌单，选 3 首周杰伦的歌";
        TraceRedactor.Result result = redactor.redact(input);
        assertFalse(result.redacted());
        assertEquals(input, result.text());
    }

    @Test
    @DisplayName("null 与空串安全")
    void nullAndEmptyAreSafe() {
        assertNull(redactor.redact(null).text());
        assertFalse(redactor.redact(null).redacted());
        assertEquals("", redactor.redact("").text());
    }
}
