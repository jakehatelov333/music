package cn.edu.seig.vibemusic.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Deployment-only first-administrator credentials; never exposed through HTTP. */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.admin-init")
public class AdminBootstrapProperties {

    private String username = "";
    private String password = "";
}
