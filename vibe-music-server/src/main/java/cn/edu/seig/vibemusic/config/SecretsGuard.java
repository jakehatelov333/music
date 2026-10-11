package cn.edu.seig.vibemusic.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动期密钥体检。
 *
 * <p>所有敏感信息都必须通过环境变量（或本地 .env 文件）注入，配置文件中只允许出现
 * {@code ${ENV_NAME:}} 形式的占位符。该类在容器启动时检查这些占位符是否真的被解析出了值，
 * 缺失时给出明确提示，避免带着空密钥运行到业务报错才发现。</p>
 *
 * <p>非 prod 环境只告警不阻断，方便本地按需启动部分功能；prod 环境缺失任一密钥直接启动失败。</p>
 */
@Slf4j
@Component
public class SecretsGuard {

    /** 配置项 -> 对应环境变量名，顺序即告警输出顺序 */
    private static final Map<String, String> REQUIRED_SECRETS = new LinkedHashMap<>();

    static {
        REQUIRED_SECRETS.put("spring.datasource.password", "MYSQL_PASSWORD");
        REQUIRED_SECRETS.put("spring.mail.username", "MAIL_USERNAME");
        REQUIRED_SECRETS.put("spring.mail.password", "MAIL_PASSWORD");
        REQUIRED_SECRETS.put("minio.accessKey", "MINIO_ACCESS_KEY");
        REQUIRED_SECRETS.put("minio.secretKey", "MINIO_SECRET_KEY");
        REQUIRED_SECRETS.put("langchain4j.dashscope.api-key", "DASHSCOPE_API_KEY");
    }

    private final Environment environment;

    @Value("${spring.profiles.active:}")
    private String activeProfile;

    public SecretsGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        List<String> missing = new ArrayList<>();
        REQUIRED_SECRETS.forEach((property, envName) -> {
            String value = environment.getProperty(property);
            if (!StringUtils.hasText(value) || isUnresolvedPlaceholder(value)) {
                missing.add(envName + "（配置项 " + property + "）");
            }
        });

        if (missing.isEmpty()) {
            log.info("密钥体检通过：所有敏感配置均由环境变量注入，未在配置文件中出现明文。");
            return;
        }

        String message = String.join("%n  - ", missing);
        String hint = String.format(
                "检测到 %d 项敏感配置缺失，请通过环境变量或项目根目录的 .env 文件注入（参考 .env.example）：%n  - %s",
                missing.size(), message);

        if (isProduction()) {
            throw new IllegalStateException(hint);
        }
        log.warn(hint);
    }

    /**
     * 占位符没有匹配到任何属性源时会原样保留 ${...}，这种情况同样视为未配置。
     */
    private boolean isUnresolvedPlaceholder(String value) {
        return value.startsWith("${") && value.endsWith("}");
    }

    private boolean isProduction() {
        if (StringUtils.hasText(activeProfile)) {
            return Arrays.stream(activeProfile.split(","))
                    .map(String::trim)
                    .anyMatch(profile -> profile.equalsIgnoreCase("prod") || profile.equalsIgnoreCase("production"));
        }
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> profile.equalsIgnoreCase("prod") || profile.equalsIgnoreCase("production"));
    }
}