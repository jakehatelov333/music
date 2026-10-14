package cn.edu.seig.vibemusic.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 记忆个性化开关。
 *
 * <p><b>「没有行」等价于「开启」：</b>绝大多数用户不会主动设置这个开关，
 * 若要求先插入一行才能开启个性化，等于让默认行为失效。因此读取侧把
 * {@code null} 行视为 {@code personalization_enabled = true}，只有显式关闭才落行。
 *
 * <p>关闭开关只影响注入提示词的「画像块 + 手动偏好块」，不删除任何偏好数据
 * （{@code overview} 仍照常返回 preferences）。
 */
@Data
public class UserAiMemorySetting {

    private Long userId;
    private Boolean personalizationEnabled;
    private LocalDateTime updatedAt;
}
