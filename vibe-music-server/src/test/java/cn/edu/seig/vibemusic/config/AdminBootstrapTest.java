package cn.edu.seig.vibemusic.config;

import cn.edu.seig.vibemusic.mapper.AdminMapper;
import cn.edu.seig.vibemusic.model.entity.Admin;
import cn.edu.seig.vibemusic.security.PasswordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminBootstrapTest {

    private AdminMapper adminMapper;
    private PasswordService passwordService;
    private AdminBootstrapProperties properties;
    private AdminBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        adminMapper = mock(AdminMapper.class);
        passwordService = new PasswordService();
        properties = new AdminBootstrapProperties();
        bootstrap = new AdminBootstrap(adminMapper, passwordService, properties);
    }

    @Test
    @DisplayName("两项都未配置：跳过且不访问数据库")
    void skipsWhenNotConfigured() {
        bootstrap.run(null);

        verify(adminMapper, never()).selectOne(any());
        verify(adminMapper, never()).insert(any(Admin.class));
    }

    @Test
    @DisplayName("只配置用户名或密码：拒绝启动")
    void rejectsPartialConfiguration() {
        properties.setUsername("root_admin");

        assertThrows(IllegalStateException.class, () -> bootstrap.run(null));
        verify(adminMapper, never()).insert(any(Admin.class));
    }

    @Test
    @DisplayName("非法用户名：拒绝启动")
    void rejectsInvalidUsername() {
        properties.setUsername("bad name");
        properties.setPassword("abc12345");

        assertThrows(IllegalStateException.class, () -> bootstrap.run(null));
        verify(adminMapper, never()).insert(any(Admin.class));
    }

    @Test
    @DisplayName("不符合统一密码规则的密码：拒绝启动")
    void rejectsWeakPassword() {
        properties.setUsername("root_admin");
        properties.setPassword("12345678");

        assertThrows(IllegalStateException.class, () -> bootstrap.run(null));
        verify(adminMapper, never()).insert(any(Admin.class));
    }

    @Test
    @DisplayName("已有管理员：不覆盖现有凭据")
    void existingAdminIsNeverOverwritten() {
        properties.setUsername("root_admin");
        properties.setPassword("abc12345");
        when(adminMapper.selectOne(any())).thenReturn(new Admin());

        bootstrap.run(null);

        verify(adminMapper, never()).insert(any(Admin.class));
    }

    @Test
    @DisplayName("新管理员：密码落库为 BCrypt，认证版本为 0")
    void createsAdminWithBcryptHash() {
        properties.setUsername("root_admin");
        properties.setPassword("abc12345");
        when(adminMapper.insert(any(Admin.class))).thenReturn(1);

        bootstrap.run(null);

        ArgumentCaptor<Admin> captor = ArgumentCaptor.forClass(Admin.class);
        verify(adminMapper).insert(captor.capture());
        Admin saved = captor.getValue();
        assertEquals("root_admin", saved.getUsername());
        assertEquals(0L, saved.getAuthVersion());
        assertTrue(saved.getPassword().startsWith("$2"));
        assertNotEquals("abc12345", saved.getPassword());
        assertTrue(passwordService.matches("abc12345", saved.getPassword()));
    }

    @Test
    @DisplayName("插入 0 行：必须失败，不能记录初始化成功")
    void insertFailureIsFatal() {
        properties.setUsername("root_admin");
        properties.setPassword("abc12345");
        when(adminMapper.insert(any(Admin.class))).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> bootstrap.run(null));
    }
}
