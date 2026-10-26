package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

public final class CurrentUser {
    private CurrentUser() {}

    public static Map<String, Object> claims() {
        return ThreadLocalUtil.get();
    }

    public static boolean isAuthenticated() {
        return claims() != null;
    }

    public static Long id() {
        return optionalId(JwtClaimsConstant.USER_ID);
    }

    public static Long optionalUserId() {
        return optionalId(JwtClaimsConstant.USER_ID);
    }

    public static Long optionalAdminId() {
        return optionalId(JwtClaimsConstant.ADMIN_ID);
    }

    public static Long requireUserId() {
        Long id = optionalUserId();
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录用户账号");
        }
        return id;
    }

    public static Long requireAdminId() {
        Long id = optionalAdminId();
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
        }
        return id;
    }

    private static Long optionalId(String claimName) {
        Map<String, Object> claims = claims();
        if (claims == null) return null;
        Object value = claims.get(claimName);
        if (value == null) return null;
        try {
            long id = Long.parseLong(value.toString());
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录身份无效");
        }
    }
}
