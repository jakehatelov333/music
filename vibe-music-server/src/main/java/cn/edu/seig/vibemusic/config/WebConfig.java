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
        // 登录接口和注册接口不拦截
        registry.addInterceptor(loginInterceptor)
                .order(0)
                .addPathPatterns("/**") // 拦截所有请求
                .excludePathPatterns(
                        "/error",
                        "/admin/login", "/admin/logout", "/admin/register",
                        "/user/login", "/user/logout", "/user/register",
                        "/user/sendVerificationCode", "/user/resetUserPassword",
                        "/banner/getBannerList",
                        // 不再整体排除：匿名仍可访问（见 LoginInterceptor 白名单），
                        // 但携带 token 时会校验身份，用于"自己创建的歌单排后 / 收藏标记"。
                        "/artist/getAllArtists", "/artist/getArtistDetail/**",
                        "/song/getAllSongs", "/song/getRecommendedSongs", "/song/getSongDetail/**",
                        PathConstant.ALBUM_LIST_PATH, PathConstant.ALBUM_DETAIL_PATH,
                        PathConstant.SEARCH_PATH);
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
