package cn.edu.seig.vibemusic.service.agent.trace;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trace 内容的脱敏器。
 *
 * <p><b>为什么必须在这里做，而不是"展示时再脱敏"：</b>一旦把明文 Token / Cookie / 密码
 * 写进数据库，它就已经落盘、进备份、可能被任何有库权限的人看到；展示层再遮盖只是
 * 遮住界面，不构成保护。因此所有快照在<b>写入前</b>就完成脱敏。
 *
 * <p><b>覆盖的凭据形态：</b>
 * <ul>
 *     <li>{@code Authorization: Bearer xxx} 与裸 {@code Bearer xxx}；</li>
 *     <li>DashScope / OpenAI 风格的 {@code sk-xxx} Key；</li>
 *     <li>JSON 中的敏感字段（token / password / secret / apiKey / cookie …），
 *         键保留、值替换为 {@code ***}，这样仍能看出"这里确实带了一个凭据"；</li>
 *     <li>形如 {@code X-Api-Key: xxx} 的自定义头。</li>
 * </ul>
 *
 * <p><b>刻意不脱敏邮箱与手机号：</b>它们是业务数据（用户账号），Trace 的价值之一就是
 * 定位到具体用户；管理员接口本身已受 {@code ROLE_ADMIN} 保护。凭据与业务身份是两回事，
 * 混在一起脱敏会让排障失去线索。
 */
@Component
public class TraceRedactor {

    /** JSON / 表单里出现这些键名时，值一律替换。 */
    private static final Pattern SENSITIVE_FIELD = Pattern.compile(
            "(?i)(\"(?:[a-z0-9_-]*(?:token|password|passwd|secret|api[_-]?key|cookie|authorization|credential)[a-z0-9_-]*)\"\\s*:\\s*)\"[^\"]*\""
                    + "|((?:^|[&\\s])(?:[a-z0-9_-]*(?:token|password|passwd|secret|api[_-]?key|cookie|authorization|credential)[a-z0-9_-]*)=)[^&\\s]+");

    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=\\-]{8,}");
    private static final Pattern API_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]{12,}");
    private static final Pattern HEADER_SECRET = Pattern.compile(
            "(?i)\\b(x-api-key|api-key|authorization|cookie|x-auth-token)\\s*[:=]\\s*[^\\s,;\"}]+");

    /**
     * @return 脱敏后的文本与"是否发生了替换"的事实
     */
    public Result redact(String value) {
        if (value == null || value.isEmpty()) return new Result(value, false);
        String current = value;
        current = replace(BEARER, current, "Bearer ***");
        current = replace(API_KEY, current, "sk-***");
        current = replace(SENSITIVE_FIELD, current, null);
        current = replace(HEADER_SECRET, current, null);
        return new Result(current, !current.equals(value));
    }

    /**
     * 敏感字段的替换要保留键名。正则第 1 组是 {@code "key": } 前缀，第 2 组是 {@code key=} 前缀，
     * 两者都为 null 时说明命中的是整段，直接整体替换。
     */
    private static String replace(Pattern pattern, String input, String fixedReplacement) {
        Matcher matcher = pattern.matcher(input);
        StringBuilder out = new StringBuilder();
        boolean changed = false;
        while (matcher.find()) {
            changed = true;
            if (fixedReplacement != null) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(fixedReplacement));
                continue;
            }
            String prefix = matcher.group(1) != null ? matcher.group(1)
                    : matcher.group(2) != null ? matcher.group(2) : "";
            String replacement = prefix.isEmpty() ? "***"
                    : prefix + (prefix.endsWith("\"") || prefix.endsWith(":") ? "\"***\"" : "***");
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        if (!changed) return input;
        matcher.appendTail(out);
        return out.toString();
    }

    public record Result(String text, boolean redacted) {
    }
}
