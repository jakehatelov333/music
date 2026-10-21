package cn.edu.seig.vibemusic.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户音乐画像。
 *
 * <p><b>显式信号与隐式信号刻意分开存：</b>
 * <ul>
 *     <li><b>显式</b>（{@code fav_}* / {@code own_}* / {@code topStyles} / {@code topArtists}）——
 *         收藏、用户自己创建的歌单。用户主动表达「我要这个」，可以直接当作偏好；</li>
 *     <li><b>隐式</b>（{@code playedSongCount} / {@code recentPlayArtists}）——
 *         播放历史。播放只是「听过」，不等于「喜欢」：可能只是好奇点开、
 *         可能只是循环列表顺带播到。因此它进<b>独立字段</b>，
 *         绝不写入 {@code topArtists}、也绝不覆盖用户显式设置的任何东西。</li>
 * </ul>
 * 合并口径见 {@code UserMusicProfileService#renderPreferenceBlock}：隐式信号只以
 * 「参考」措辞出现，且数量门槛由 SQL 侧的不同歌曲数承担（单次播放进不了 TOP N）。
 *
 * <p>{@code topStyles} / {@code topArtists} / {@code recentPlayArtists} 存 JSON 数组文本
 * （与 {@code tb_agent_pending_action.draft_json} 同样的做法），由服务层用 Jackson 解析。
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
    /**
     * 有过有效播放的不同歌曲数（隐式信号）。与 {@code favSongCount} 分开统计，两者不合并。
     */
    private Integer playedSongCount;
    /**
     * 反复播放的歌手 TOP N（隐式信号，JSON 数组文本）。
     * <b>不写入</b> {@code topArtists}：播放不等于喜欢，不能冒充用户显式表达的偏好。
     */
    private String recentPlayArtists;
    /** qwen-flash 生成的偏好概述；为 null 时提示词里只注入结构化事实。 */
    private String prefSummary;
    /** 生成 prefSummary 时的事实指纹；与当前事实不一致说明概述已过期。 */
    private String summarySourceHash;
    private Long version;
    private LocalDateTime updatedAt;
}
