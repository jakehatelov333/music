package cn.edu.seig.vibemusic.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户音乐画像。
 *
 * <p><b>数据来源只有两处</b>：收藏（{@code tb_user_favorite}）与用户自建歌单
 * （{@code tb_playlist} + {@code tb_playlist_binding}）。项目尚无播放历史表，
 * 因此这里<b>不声称</b>知道用户听过什么 —— 字段名一律用 {@code fav_} / {@code own_} 前缀，
 * 避免以后被误读成"播放偏好"。
 *
 * <p>{@code topStyles} / {@code topArtists} 存 JSON 数组文本（与
 * {@code tb_agent_pending_action.draft_json} 同样的做法），由服务层用 Jackson 解析。
 *
 * <p>{@code prefSummary} 与 {@code summarySourceHash} 是"自然语言概述"的缓存：
 * 前者由 qwen-flash 异步生成，后者记录生成时的事实指纹，指纹不变就不重复烧 token。
 */
@Data
public class UserMusicProfile {

    private Long userId;
    private String topStyles;
    private String topArtists;
    private Integer favSongCount;
    private Integer favPlaylistCount;
    private Integer ownPlaylistCount;
    private Integer ownPlaylistSongCount;
    /** qwen-flash 生成的偏好概述；为 null 时提示词里只注入结构化事实。 */
    private String prefSummary;
    /** 生成 prefSummary 时的事实指纹；与当前事实不一致说明概述已过期。 */
    private String summarySourceHash;
    private Long version;
    private LocalDateTime updatedAt;
}
