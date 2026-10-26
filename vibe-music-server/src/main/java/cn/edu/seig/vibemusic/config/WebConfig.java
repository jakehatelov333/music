package cn.edu.seig.vibemusic.config;

import cn.edu.seig.vibemusic.constant.PathConstant;
import cn.edu.seig.vibemusic.interceptor.LoginInterceptor;
import cn.edu.seig.vibemusic.interceptor.RateLimitInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.CharacterEncodingFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private LoginInterceptor loginInterceptor;

    @Autowired
    private RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 登录/注册等入口不拦截。注意：/admin/register 已于 2026-10-15 移出白名单（该端点同时下线），
        // 新增管理员走受控入口，不要再把任何管理员开户路径加回这里。
        registry.addInterceptor(loginInterceptor)
                .order(0)
                .addPathPatterns("/**") // 拦截所有请求
                .excludePathPatterns(
                        "/error",
                        "/admin/login", "/admin/logout",
                        "/user/login", "/user/logout", "/user/register",
                        "/user/sendVerificationCode", "/user/resetUserPassword",
                        "/banner/getBannerList");
        // 公共内容详情不再整体排除：匿名由 LoginInterceptor 白名单放行，携带 token
        // 时必须完成 Redis 会话校验，不能仅凭可解析的 JWT 获取用户态数据。
        registry.addInterceptor(rateLimitInterceptor).order(1).addPathPatterns("/**");
    }

    @Bean
    public CharacterEncodingFilter characterEncodingFilter() {
        CharacterEncodingFilter filter = new CharacterEncodingFilter();
        filter.setEncoding("UTF-8");
        filter.setForceEncoding(true);
        return filter;
    }
}
