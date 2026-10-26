package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.mapper.UserMapper;
import cn.edu.seig.vibemusic.model.dto.UserDTO;
import cn.edu.seig.vibemusic.model.entity.User;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户资料更新的身份隔离验收。
 *
 * <p>背景：旧实现把认证身份取出来只用于重名比较，随后 {@code BeanUtils.copyProperties(userDTO, user)}
 * 把请求体里的 userId 复制进实体，{@code updateById} 因此按调用者指定的主键更新，
 * 任意登录用户可改写他人资料。
 *
 * <p>兼容性约束：客户端 {@code ProfilePanel.vue} 会提交本人 userId，所以"缺省或等于本人"必须继续可用。
 */
class UserProfileUpdateIsolationTest {

    private static final long SELF = 1L;
    private static final long OTHER = 2L;

    private UserMapper userMapper;
    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ThreadLocalUtil.set(Map.of(JwtClaimsConstant.USER_ID, SELF, JwtClaimsConstant.ROLE, "ROLE_USER"));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalUtil.remove();
    }

    @Test
    @DisplayName("提交他人 userId：403，且不执行任何更新")
    void rejectsForeignUserId() {
        var ex = assertThrows(ResponseStatusException.class, () -> service.updateUserInfo(dto(OTHER)));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    @DisplayName("越权判断排在唯一性检查之前：越权请求不产生任何查重查询")
    void rejectsBeforeRunningDuplicateChecks() {
        assertThrows(ResponseStatusException.class, () -> service.updateUserInfo(dto(OTHER)));

        verify(userMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("提交本人 userId：更新目标是本人")
    void acceptsOwnUserId() {
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        assertEquals(0, service.updateUserInfo(dto(SELF)).getCode());
        assertEquals(SELF, capturedUpdatedUserId());
        assertEquals(null, capturedUpdatedUser().getEmail(), "资料更新不得改写恢复邮箱");
    }

    @Test
    @DisplayName("不传 userId：更新目标仍是本人，实体主键不丢")
    void acceptsMissingUserId() {
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        assertEquals(0, service.updateUserInfo(dto(null)).getCode());
        assertEquals(SELF, capturedUpdatedUserId());
        assertEquals(null, capturedUpdatedUser().getEmail());
    }

    @Test
    @DisplayName("认证上下文缺失：401，不执行更新")
    void requiresAuthenticatedContext() {
        ThreadLocalUtil.remove();

        var ex = assertThrows(ResponseStatusException.class, () -> service.updateUserInfo(dto(SELF)));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
        verify(userMapper, never()).updateById(any(User.class));
    }

    private long capturedUpdatedUserId() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        return captor.getValue().getUserId();
    }

    private User capturedUpdatedUser() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper, atLeastOnce()).updateById(captor.capture());
        return captor.getAllValues().get(captor.getAllValues().size() - 1);
    }

    private UserDTO dto(Long userId) {
        UserDTO dto = new UserDTO();
        dto.setUserId(userId);
        dto.setUsername("tester");
        dto.setPhone("13800000000");
        dto.setEmail("tester@example.com");
        dto.setIntroduction("hello");
        return dto;
    }
}
