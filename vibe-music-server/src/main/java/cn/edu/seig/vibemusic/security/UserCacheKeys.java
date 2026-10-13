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
            // 命名空间版本号：缓存值（VO）的字段结构或结果集语义发生不兼容变化时必须递增，
            // 否则旧结构的缓存会继续命中，前端拿到缺字段/错行的旧数据。
            //  v1 → v2：键中加入用户身份，避免跨用户串数据。
            //  v2 → v3：SongVO 新增 artists（结构化歌手引用，供跳转歌手页）。
            //  v3 → v4：收藏歌曲查询改为按当前用户 JOIN，修掉多用户收藏同一首歌时的重复行。
            return "v4:" + business + ":" + userId + ":" + objectMapper.writeValueAsString(query);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法生成缓存键");
        }
    }
}
