package cn.edu.seig.vibemusic.interceptor;


import cn.edu.seig.vibemusic.config.RolePermissionManager;
import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.constant.MessageConstant;
import cn.edu.seig.vibemusic.constant.PathConstant;
import cn.edu.seig.vibemusic.security.AuthSessionService;
import cn.edu.seig.vibemusic.security.TokenExtractor;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import cn.edu.seig.vibemusic.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class LoginInterceptor implements AsyncHandlerInterceptor {

    @Autowired
    private AuthSessionService authSessionService;
    @Autowired
    private RolePermissionManager rolePermissionManager;

    @Autowired
    private ObjectMapper objectMapper;

    public void sendErrorResponse(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8"); // 设置字符编码为UTF-8
        response.setContentType("application/json;charset=UTF-8"); // 设置响应的Content-Type
        objectMapper.writeValue(response.getWriter(), Result.error(message));
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        ThreadLocalUtil.remove();
        if (request.getDispatcherType() == DispatcherType.ASYNC) return true;
        // 允许 CORS 预检请求（OPTIONS 方法）直接通过
        if (request.getMethod().equalsIgnoreCase("OPTIONS")) {
            response.setStatus(HttpServletResponse.SC_OK);
            return true; // 直接放行，确保 CORS 预检请求不会被拦截
        }

        String token;
        try {
            token = TokenExtractor.extract(request.getHeader("Authorization"));
        } catch (RuntimeException e) {
            sendErrorResponse(response, 401, MessageConstant.SESSION_EXPIRED);
            return false;
        }
        String path = request.getRequestURI();

        // 获取 Spring 的 PathMatcher 实例
        PathMatcher pathMatcher = new AntPathMatcher();

        // 定义允许访问的路径
        List<String> allowedPaths = Arrays.asList(
                PathConstant.PLAYLIST_DETAIL_PATH,
                PathConstant.PLAYLIST_LIST_PATH,
                "/playlist/getRecommendedPlaylists",
                PathConstant.ARTIST_DETAIL_PATH,
                PathConstant.SONG_LIST_PATH,
                PathConstant.SONG_DETAIL_PATH,
                PathConstant.ALBUM_LIST_PATH,
                PathConstant.ALBUM_DETAIL_PATH,
                PathConstant.SEARCH_PATH,
                PathConstant.SEARCH_SUBPATH,
                PathConstant.AI_PATH
        );

        // 检查路径是否匹配
        boolean isAllowedPath = allowedPaths.stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, path));

        log.debug("拦截器检查: path={}, allowedPaths={}, isAllowed={}", path, allowedPaths, isAllowedPath);

        if (token == null) {
            if (isAllowedPath) {
                log.debug("放行未登录用户访问: {}", path);
                return true; // 允许未登录用户访问这些路径
            }

            log.debug("拒绝未登录用户访问: {}", path);
            sendErrorResponse(response, 401, MessageConstant.NOT_LOGIN); // 缺少令牌
            return false;
        }

        try {
            Map<String, Object> claims = authSessionService.authenticate(token);
            String role = String.valueOf(claims.get(JwtClaimsConstant.ROLE));
            String requestURI = request.getRequestURI();
            if (isAllowedPath || rolePermissionManager.hasPermission(role, requestURI)) {
                ThreadLocalUtil.set(claims);
                return true;
            } else {
                sendErrorResponse(response, 403, MessageConstant.NO_PERMISSION); // 无权限访问
                return false;
            }
        } catch (org.springframework.dao.DataAccessException e) {
            throw e;
        } catch (org.springframework.web.server.ResponseStatusException e) {
            sendErrorResponse(response, e.getStatusCode().value(), MessageConstant.SESSION_EXPIRED);
            return false;
        } catch (Exception e) {
            sendErrorResponse(response, 401, MessageConstant.SESSION_EXPIRED); // 令牌无效
            return false;
        }
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
        ThreadLocalUtil.remove();
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 清空ThreadLocal中的数据
        ThreadLocalUtil.remove();
    }
}
