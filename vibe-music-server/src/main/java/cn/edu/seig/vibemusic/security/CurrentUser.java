package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

public final class CurrentUser {
    private CurrentUser() {}

    public static Long id() {
        Map<String, Object> claims = ThreadLocalUtil.get();
        if (claims == null) return null;
        Object value = claims.get(JwtClaimsConstant.USER_ID);
        if (value == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录身份无效");
        try {
            long id = Long.parseLong(value.toString());
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录身份无效");
        }
    }
}
