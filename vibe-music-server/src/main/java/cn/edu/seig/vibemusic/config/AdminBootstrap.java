package cn.edu.seig.vibemusic.config;

import cn.edu.seig.vibemusic.constant.PasswordRules;
import cn.edu.seig.vibemusic.mapper.AdminMapper;
import cn.edu.seig.vibemusic.model.entity.Admin;
import cn.edu.seig.vibemusic.security.PasswordService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * One-shot administrator provisioning from deployment secrets.
 *
 * <p>The HTTP registration endpoint stays removed. A username that already exists is
 * never modified, so restarting with stale environment variables cannot reset an
 * administrator's password or re-enable a disabled account.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {

    private final AdminMapper adminMapper;
    private final PasswordService passwordService;
    private final AdminBootstrapProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        String username = properties.getUsername();
        String password = properties.getPassword();
        boolean hasUsername = StringUtils.hasText(username);
        boolean hasPassword = StringUtils.hasText(password);

        if (!hasUsername && !hasPassword) {
            log.info("未配置 ADMIN_INIT_USERNAME / ADMIN_INIT_PASSWORD，跳过管理员初始化");
            return;
        }
        if (hasUsername != hasPassword) {
            throw new IllegalStateException(
                    "ADMIN_INIT_USERNAME 与 ADMIN_INIT_PASSWORD 必须同时配置或同时留空");
        }

        String normalizedUsername = username.trim();
        if (!normalizedUsername.matches("^[a-zA-Z0-9_-]{4,16}$")) {
            throw new IllegalStateException("ADMIN_INIT_USERNAME 必须为 4-16 位字母、数字、下划线或连字符");
        }
        if (!password.matches(PasswordRules.PATTERN)) {
            throw new IllegalStateException("ADMIN_INIT_PASSWORD " + PasswordRules.MESSAGE);
        }

        Admin existing = adminMapper.selectOne(
                new QueryWrapper<Admin>().eq("username", normalizedUsername));
        if (existing != null) {
            log.info("管理员 {} 已存在，初始化不会覆盖现有凭据", normalizedUsername);
            return;
        }

        Admin admin = new Admin()
                .setUsername(normalizedUsername)
                .setPassword(passwordService.encode(password))
                .setAuthVersion(0L);
        if (adminMapper.insert(admin) != 1) {
            throw new IllegalStateException("初始管理员创建失败");
        }
        log.info("已创建初始管理员 {}", normalizedUsername);
    }
}
