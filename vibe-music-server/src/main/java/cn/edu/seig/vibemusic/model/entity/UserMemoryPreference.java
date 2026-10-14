package cn.edu.seig.vibemusic.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户手动指定的 AI 偏好。
 *
 * <p><b>这是「手动层」，与自动画像 {@code tb_user_music_profile} 完全隔离：</b>
 * 自动画像由收藏与自建歌单聚合刷新，任何时候都不会写这张表；反之刷新画像也不会
 * 覆盖这里的手动偏好。最终生效的偏好是运行时把两层合并出来的（见
 * {@code UserMemoryPreferenceService#overview}）。
 *
 * <p>{@code category} 存 {@code MemoryPreferenceCategory} 的枚举名；
 * {@code targetId} 只在歌手类有值（artist_id），风格/自由文本类为 null；
 * {@code targetValue} 是规范化后的文本（歌手名由后端按 id 解析，风格名落在派生集合内）。
 */
@Data
public class UserMemoryPreference {

    private Long id;
    private Long userId;
    /** MemoryPreferenceCategory 的枚举名。 */
    private String category;
    /** 结构化目标 id：歌手=artist_id；风格/自由文本=NULL。 */
    private Long targetId;
    /** 规范化文本：歌手名/风格名/自由文本。 */
    private String targetValue;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
