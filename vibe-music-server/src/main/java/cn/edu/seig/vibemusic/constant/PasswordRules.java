package cn.edu.seig.vibemusic.constant;

/**
 * 统一的密码强度规则。
 *
 * <p>规则定义（注册 / 修改密码 / 重置密码三个入口必须完全一致）：
 * <ul>
 *     <li>长度 8–18；</li>
 *     <li>必须同时包含至少一个 ASCII 字母和至少一个 ASCII 数字；</li>
 *     <li>允许的字符为可见 ASCII（{@code 0x21}–{@code 0x7E}，即字母、数字与常见英文符号）；</li>
 *     <li>不允许空格（{@code 0x20}），也不允许中文等非 ASCII 字符。</li>
 * </ul>
 *
 * <p>{@link #PATTERN} 与 {@link #MESSAGE} 均为编译期常量，可直接用于
 * {@code @Pattern(regexp = PasswordRules.PATTERN, message = PasswordRules.MESSAGE)}。
 */
public final class PasswordRules {

    /**
     * 密码正则：先分别断言存在字母、存在数字，再限定整串为 8–18 个可见 ASCII 字符。
     * 可见 ASCII 范围天然排除了空格（0x20）与所有非 ASCII 字符（如中文）。
     */
    public static final String PATTERN = "^(?=.*[A-Za-z])(?=.*[0-9])[\\x21-\\x7E]{8,18}$";

    /** 密码规则不满足时的提示信息。 */
    public static final String MESSAGE = "密码为 8-18 位，必须同时包含字母和数字，不能包含空格";

    private PasswordRules() {
    }
}
