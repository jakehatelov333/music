package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.config.CorsConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CorsSecurityTest {
    @Configuration @EnableWebMvc @Import(CorsConfig.class)
    static class Config { @Bean Endpoint endpoint() { return new Endpoint(); } }
    @RestController
    static class Endpoint { @PostMapping("/ai/chat") String chat() { return "ok"; } }

    @Test void onlyConfiguredFrontendCanMakeCredentialedRequests() throws Exception {
        try (AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(Config.class);
            context.refresh();
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(options("/ai/chat").header("Origin", "http://localhost:8090")
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8090"))
                    .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
            mvc.perform(options("/ai/chat").header("Origin", "https://untrusted.example")
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
    }
}
