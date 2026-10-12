package cn.edu.seig.vibemusic.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component("userCacheKeys")
@RequiredArgsConstructor
public class UserCacheKeys {
    private final ObjectMapper objectMapper;

    public String key(String business, Object query) {
        Long userId = CurrentUser.id();
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        try {
            // 新命名空间不会命中旧的、缺少用户身份的缓存。
            return "v2:" + business + ":" + userId + ":" + objectMapper.writeValueAsString(query);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法生成缓存键");
        }
    }
}
