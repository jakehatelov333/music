package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.constant.PasswordRules;
import cn.edu.seig.vibemusic.model.dto.AdminDTO;
import cn.edu.seig.vibemusic.model.dto.UserAddDTO;
import cn.edu.seig.vibemusic.model.dto.UserLoginDTO;
import cn.edu.seig.vibemusic.model.dto.UserPasswordDTO;
import cn.edu.seig.vibemusic.model.dto.UserRegisterDTO;
import cn.edu.seig.vibemusic.model.dto.UserResetPasswordDTO;
import cn.edu.seig.vibemusic.model.entity.Admin;
import cn.edu.seig.vibemusic.model.entity.User;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一密码规则（{@link PasswordRules}）的验收：
 * 既直接验证正则行为，也用真实的 Bean Validation 校验三个入口 DTO，
 * 确保它们用的确实是同一份规则，而不是各写各的。
 */
class PasswordRuleTest {

    private static final String[] INVALID = {
            "12345678",            // 纯数字
            "abcdefgh",            // 纯字母
            "!@#$%^&*",            // 纯符号
            "abc1234",             // 7 位，长度不足
            "abc1234567890123456", // 19 位，超长
            "abc 1234",            // 含空格
            "密码abc1234",          // 含中文
            "abc１２３４５6",        // 含全角数字（非 ASCII）
    };

    private static final String[] VALID = {
            "abc12345",
            "abc123!@",
            "Abcdefg1",
            "a1!bcdef",
            "ABC123456789012345", // 恰好 18 位
            "abc12345678",
    };

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    @DisplayName("正则行为：纯数字/纯字母/纯符号/超短/超长/空格/中文一律拒绝，字母+数字接受")
    void patternAcceptsOnlyValidPasswords() {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(PasswordRules.PATTERN);
        for (String password : INVALID) {
            assertFalse(pattern.matcher(password).matches(), "应拒绝: " + password);
        }
        for (String password : VALID) {
            assertTrue(pattern.matcher(password).matches(), "应接受: " + password);
        }
    }

    @Test
    @DisplayName("注册 DTO：password 字段使用统一规则，非法密码被拒、合法密码通过")
    void registerDtoUsesUnifiedRule() {
        for (String password : INVALID) {
            Set<ConstraintViolation<UserRegisterDTO>> violations = validator.validate(register(password));
            assertTrue(hasViolationOn(violations, "password"), "注册非法密码应被拒: " + password);
        }
        for (String password : VALID) {
            Set<ConstraintViolation<UserRegisterDTO>> violations = validator.validate(register(password));
            assertFalse(hasViolationOn(violations, "password"), "注册合法密码不应被拒: " + password);
        }
    }

    @Test
    @DisplayName("改密 DTO：newPassword 用统一规则；oldPassword/repeatPassword 不受格式约束（历史旧密码可改密）")
    void passwordDtoUsesUnifiedRuleForNewPasswordOnly() {
        for (String password : INVALID) {
            Set<ConstraintViolation<UserPasswordDTO>> violations = validator.validate(change("abc12345", password));
            assertTrue(hasViolationOn(violations, "newPassword"), "改密非法新密码应被拒: " + password);
        }
        for (String password : VALID) {
            Set<ConstraintViolation<UserPasswordDTO>> violations = validator.validate(change("abc12345", password));
            assertFalse(hasViolationOn(violations, "newPassword"), "改密合法新密码不应被拒: " + password);
        }

        // 旧密码不符合新规则但非空：不得被 Bean Validation 拦下（否则历史用户永远改不了密码）
        Set<ConstraintViolation<UserPasswordDTO>> legacy = validator.validate(change("123", "abc12345"));
        assertFalse(hasViolationOn(legacy, "oldPassword"), "历史旧密码不应被格式校验拦住");
        assertTrue(legacy.isEmpty(), "旧密码=123、新密码合法时不应有任何校验错误");

        // repeatPassword 也不做格式校验，相等性由 service 校验
        UserPasswordDTO mismatch = change("abc12345", "abc12345");
        mismatch.setRepeatPassword("!!!");
        Set<ConstraintViolation<UserPasswordDTO>> repeat = validator.validate(mismatch);
        assertFalse(hasViolationOn(repeat, "repeatPassword"), "repeatPassword 不应被格式校验拦住");
    }

    @Test
    @DisplayName("重置 DTO：newPassword 用统一规则，repeatPassword 只校验非空")
    void resetDtoUsesUnifiedRule() {
        for (String password : INVALID) {
            Set<ConstraintViolation<UserResetPasswordDTO>> violations = validator.validate(reset(password));
            assertTrue(hasViolationOn(violations, "newPassword"), "重置非法新密码应被拒: " + password);
        }
        for (String password : VALID) {
            Set<ConstraintViolation<UserResetPasswordDTO>> violations = validator.validate(reset(password));
            assertFalse(hasViolationOn(violations, "newPassword"), "重置合法新密码不应被拒: " + password);
        }
        UserResetPasswordDTO dto = reset("abc12345");
        dto.setRepeatPassword("x");
        assertFalse(hasViolationOn(validator.validate(dto), "repeatPassword"),
                "repeatPassword 不应被格式校验拦住");
    }

    @Test
    @DisplayName("DTO 注解上的正则确实等于 PasswordRules.PATTERN（三个入口一致）")
    void dtoAnnotationsReferenceSharedConstant() throws Exception {
        assertPatternEqualsShared(UserRegisterDTO.class, "password");
        assertPatternEqualsShared(UserPasswordDTO.class, "newPassword");
        assertPatternEqualsShared(UserResetPasswordDTO.class, "newPassword");

        // oldPassword / repeatPassword 不应再有 @Pattern
        assertNull(patternOf(UserPasswordDTO.class, "oldPassword"));
        assertNull(patternOf(UserPasswordDTO.class, "repeatPassword"));
        assertNull(patternOf(UserResetPasswordDTO.class, "repeatPassword"));
    }

    @Test
    @DisplayName("登录 DTO：不施加强度约束，纯数字密码可登录，空密码被拒")
    void loginDtoDoesNotEnforcePasswordStrength() throws Exception {
        // 长度合规但不符合新规则的纯数字密码：登录必须放行，否则老用户被挡在门外
        assertTrue(validator.validate(login("12345678")).isEmpty(), "登录不应被密码强度规则拦住");
        assertNull(patternOf(UserLoginDTO.class, "password"), "登录 DTO 的 password 不应有 @Pattern");

        UserLoginDTO blank = login(" ");
        assertTrue(hasViolationOn(validator.validate(blank), "password"), "空密码应被 @NotBlank 拒绝");
    }

    @Test
    @DisplayName("管理员新建用户 DTO：password 使用统一规则")
    void userAddDtoUsesUnifiedRule() throws Exception {
        assertPatternEqualsShared(UserAddDTO.class, "password");
        assertTrue(hasViolationOn(validator.validate(userAdd("12345678")), "password"),
                "管理员新建用户的纯数字密码应被拒");
        assertFalse(hasViolationOn(validator.validate(userAdd("abc12345")), "password"),
                "管理员新建用户的合法密码不应被拒");
    }

    @Test
    @DisplayName("管理员 DTO：注册与登录复用，密码只校验非空")
    void adminDtoDoesNotEnforcePasswordStrength() throws Exception {
        assertNull(patternOf(AdminDTO.class, "password"), "AdminDTO.password 不应有 @Pattern");
        assertTrue(validator.validate(admin("12345678")).isEmpty(),
                "AdminDTO 复用注册与登录，纯数字密码不应被格式拦住");
        assertTrue(hasViolationOn(validator.validate(admin(" ")), "password"),
                "AdminDTO 空密码应被 @NotBlank 拒绝");
    }

    @Test
    @DisplayName("实体上的密码 @Pattern 是死注解，已移除")
    void entitiesHaveNoPasswordPattern() throws Exception {
        assertNull(patternOf(User.class, "password"), "User 实体 password 不应有 @Pattern");
        assertNull(patternOf(Admin.class, "password"), "Admin 实体 password 不应有 @Pattern");
    }

    private static void assertPatternEqualsShared(Class<?> type, String field) throws Exception {
        Pattern annotation = patternOf(type, field);
        assertNotNull(annotation, type.getSimpleName() + "." + field + " 缺少 @Pattern");
        assertTrue(annotation.regexp().equals(PasswordRules.PATTERN),
                type.getSimpleName() + "." + field + " 的 @Pattern 与 PasswordRules.PATTERN 不一致");
        assertTrue(annotation.message().equals(PasswordRules.MESSAGE),
                type.getSimpleName() + "." + field + " 的提示信息与 PasswordRules.MESSAGE 不一致");
    }

    private static Pattern patternOf(Class<?> type, String field) throws Exception {
        Field declared = type.getDeclaredField(field);
        return declared.getAnnotation(Pattern.class);
    }

    private static boolean hasViolationOn(Set<? extends ConstraintViolation<?>> violations, String property) {
        return violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals(property));
    }

    private static UserRegisterDTO register(String password) {
        UserRegisterDTO dto = new UserRegisterDTO();
        dto.setUsername("user_1234");
        dto.setEmail("user@example.com");
        dto.setVerificationCode("Abc123");
        dto.setPassword(password);
        return dto;
    }

    private static UserPasswordDTO change(String oldPassword, String newPassword) {
        UserPasswordDTO dto = new UserPasswordDTO();
        dto.setOldPassword(oldPassword);
        dto.setNewPassword(newPassword);
        dto.setRepeatPassword(newPassword);
        return dto;
    }

    private static UserResetPasswordDTO reset(String newPassword) {
        UserResetPasswordDTO dto = new UserResetPasswordDTO();
        dto.setEmail("user@example.com");
        dto.setVerificationCode("Abc123");
        dto.setNewPassword(newPassword);
        dto.setRepeatPassword(newPassword);
        return dto;
    }

    private static UserLoginDTO login(String password) {
        UserLoginDTO dto = new UserLoginDTO();
        dto.setEmail("user@example.com");
        dto.setPassword(password);
        return dto;
    }

    private static UserAddDTO userAdd(String password) {
        UserAddDTO dto = new UserAddDTO();
        dto.setUsername("user_1234");
        dto.setEmail("user@example.com");
        dto.setPassword(password);
        return dto;
    }

    private static AdminDTO admin(String password) {
        AdminDTO dto = new AdminDTO();
        dto.setUsername("admin_123");
        dto.setPassword(password);
        return dto;
    }
}
