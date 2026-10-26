package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.constant.MessageConstant;
import cn.edu.seig.vibemusic.enumeration.UserStatusEnum;
import cn.edu.seig.vibemusic.mapper.AdminMapper;
import cn.edu.seig.vibemusic.mapper.UserMapper;
import cn.edu.seig.vibemusic.model.dto.AdminDTO;
import cn.edu.seig.vibemusic.model.dto.UserLoginDTO;
import cn.edu.seig.vibemusic.model.entity.Admin;
import cn.edu.seig.vibemusic.model.entity.User;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.impl.AdminServiceImpl;
import cn.edu.seig.vibemusic.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginEnumerationTest {

    private UserMapper userMapper;
    private AdminMapper adminMapper;
    private PasswordService passwordService;
    private UserServiceImpl users;
    private AdminServiceImpl admins;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        adminMapper = mock(AdminMapper.class);
        passwordService = mock(PasswordService.class);

        users = new UserServiceImpl();
        ReflectionTestUtils.setField(users, "userMapper", userMapper);
        ReflectionTestUtils.setField(users, "passwordService", passwordService);

        admins = new AdminServiceImpl();
        ReflectionTestUtils.setField(admins, "adminMapper", adminMapper);
        ReflectionTestUtils.setField(admins, "passwordService", passwordService);
    }

    @Test
    @DisplayName("用户登录：未知账号、错误密码、禁用账号返回完全相同")
    void userLoginFailuresAreIndistinguishable() {
        when(userMapper.selectOne(any())).thenReturn(null);
        Result unknown = users.login(user("missing@example.com", "abc12345"));

        User enabled = user(UserStatusEnum.ENABLE, "abc12345");
        when(userMapper.selectOne(any())).thenReturn(enabled);
        when(passwordService.matches(anyString(), anyString())).thenReturn(false);
        Result wrongPassword = users.login(user("user@example.com", "wrong123"));

        User disabled = user(UserStatusEnum.DISABLE, "abc12345");
        when(userMapper.selectOne(any())).thenReturn(disabled);
        when(passwordService.matches(anyString(), anyString())).thenReturn(true);
        Result disabledResult = users.login(user("user@example.com", "abc12345"));

        assertEquals(MessageConstant.LOGIN + MessageConstant.FAILED, unknown.getMessage());
        assertEquals(unknown.getMessage(), wrongPassword.getMessage());
        assertEquals(unknown.getMessage(), disabledResult.getMessage());
        assertEquals(unknown.getCode(), wrongPassword.getCode());
        assertEquals(unknown.getCode(), disabledResult.getCode());
    }

    @Test
    @DisplayName("用户不存在时执行一次 dummyMatch，存在时不再额外 dummyMatch")
    void userUnknownAccountPaysOneDummyHash() {
        when(userMapper.selectOne(any())).thenReturn(null);

        users.login(user("missing@example.com", "abc12345"));

        verify(passwordService, times(1)).dummyMatch("abc12345");
        verify(passwordService, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("管理员登录：未知账号与错误密码返回完全相同")
    void adminLoginFailuresAreIndistinguishable() {
        when(adminMapper.selectOne(any())).thenReturn(null);
        Result unknown = admins.login(admin("missing_admin", "abc12345"));

        Admin existing = new Admin().setAdminId(1L).setUsername("root_admin")
                .setPassword("$2a$12$stored").setAuthVersion(0L);
        when(adminMapper.selectOne(any())).thenReturn(existing);
        when(passwordService.matches(anyString(), anyString())).thenReturn(false);
        Result wrongPassword = admins.login(admin("root_admin", "wrong123"));

        assertEquals(MessageConstant.LOGIN + MessageConstant.FAILED, unknown.getMessage());
        assertEquals(unknown.getMessage(), wrongPassword.getMessage());
        assertEquals(unknown.getCode(), wrongPassword.getCode());
    }

    private static User user(UserStatusEnum status, String rawPassword) {
        return new User().setUserId(1L).setUsername("user_1234")
                .setEmail("user@example.com").setUserStatus(status)
                .setPassword(DigestUtils.md5DigestAsHex(
                        rawPassword.getBytes(StandardCharsets.UTF_8)));
    }

    private static UserLoginDTO user(String email, String password) {
        UserLoginDTO dto = new UserLoginDTO();
        dto.setEmail(email);
        dto.setPassword(password);
        return dto;
    }

    private static AdminDTO admin(String username, String password) {
        AdminDTO dto = new AdminDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        return dto;
    }
}
