package cn.edu.seig.vibemusic.config;

import cn.edu.seig.vibemusic.memory.ChatMemoryHolder;
import jakarta.servlet.*;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class ChatMemoryCleanupFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            chain.doFilter(request, response);
        } finally {
            ChatMemoryHolder.clear();
        }
    }
}
