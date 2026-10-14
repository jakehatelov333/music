package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.constant.MessageConstant;
import cn.edu.seig.vibemusic.mapper.UserMapper;
import cn.edu.seig.vibemusic.model.dto.UserPasswordDTO;
import cn.edu.seig.vibemusic.model.entity.User;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.DigestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 修改密码服务层的业务顺序验收。
 *
 * <p>关键点：DTO 层不再对旧密码做格式校验，因此「旧密码不符合新规则但确实正确」必须能改密；
 * 而「两次新密码不一致」的校验不能被任何格式校验提前拦截。
 */
class UserUpdatePasswordTest {

    private UserMapper userMapper;
    private StringRedisTemplate redis;
    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        redis = mock(StringRedisTemplate.class);
        service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ThreadLocalUtil.set(Map.of(JwtClaimsConstant.USER_ID, 1L, JwtClaimsConstant.ROLE, "ROLE_USER"));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalUtil.remove();
    }

    @Test
    @DisplayName("旧密码错误：返回原密码错误，不更新、不作废 token")
    void rejectsWrongOldPassword() {
        when(userMapper.selectById(1L)).thenReturn(userWithPassword("old12345"));

        Result result = service.updateUserPassword(dto("wrong123", "new12345", "new12345"), "tok");

        assertEquals(1, result.getCode());
        assertEquals(MessageConstant.OLD_PASSWORD_ERROR, result.getMessage());
        verify(userMapper, never()).update(any(), any());
        verify(redis, never()).delete(anyString());
    }

    @Test
    @DisplayName("新旧密码相同：拒绝")
    void rejectsSamePassword() {
        when(userMapper.selectById(1L)).thenReturn(userWithPassword("same1234"));

        Result result = service.updateUserPassword(dto("same1234", "same1234", "same1234"), "tok");

        assertEquals(1, result.getCode());
        assertEquals(MessageConstant.NEW_PASSWORD_ERROR, result.getMessage());
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("两次新密码不一致：拒绝")
    void rejectsMismatchedRepeat() {
        when(userMapper.selectById(1L)).thenReturn(userWithPassword("old12345"));

        Result result = service.updateUserPassword(dto("old12345", "new12345", "diff1234"), "tok");

        assertEquals(1, result.getCode());
        assertEquals(MessageConstant.PASSWORD_NOT_MATCH, result.getMessage());
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("成功路径：更新密码并作废 token")
    void updatesPasswordAndInvalidatesToken() {
        when(userMapper.selectById(1L)).thenReturn(userWithPassword("old12345"));
        when(userMapper.update(any(), any())).thenReturn(1);

        Result result = service.updateUserPassword(dto("old12345", "new12345", "new12345"), "tok");

        assertEquals(0, result.getCode());
        verify(userMapper).update(any(), any());
        verify(redis).delete("tok");
    }

    @Test
    @DisplayName("旧密码不符合新规则但正确：必须允许修改")
    void allowsLegacyOldPassword() {
        // "123" 是纯数字，按新规则不合法；但它是该用户真实的旧密码，不能被拦下
        when(userMapper.selectById(1L)).thenReturn(userWithPassword("123"));
        when(userMapper.update(any(), any())).thenReturn(1);

        Result result = service.updateUserPassword(dto("123", "abc12345", "abc12345"), "tok");

        assertEquals(0, result.getCode(), "旧密码格式不合规但正确时应允许改密");
        verify(redis).delete("tok");
    }

    private static User userWithPassword(String rawPassword) {
        User user = new User();
        user.setUserId(1L);
        user.setPassword(DigestUtils.md5DigestAsHex(rawPassword.getBytes()));
        return user;
    }

    private static UserPasswordDTO dto(String oldPassword, String newPassword, String repeatPassword) {
        UserPasswordDTO dto = new UserPasswordDTO();
        dto.setOldPassword(oldPassword);
        dto.setNewPassword(newPassword);
        dto.setRepeatPassword(repeatPassword);
        return dto;
    }
}
