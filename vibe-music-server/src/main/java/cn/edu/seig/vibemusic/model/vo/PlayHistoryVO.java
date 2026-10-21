package cn.edu.seig.vibemusic.model.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 播放历史列表项：历史信息 + 播放所需的歌曲信息。
 *
 * <p>刻意实现 {@link SongArtistAware}，因此结构化歌手列表由
 * {@code SongArtistService#attachArtists} 统一装配，不需要为这个视图再写一份。
 *
 * <p>字段与 {@link SongVO} 保持同名同义（{@code songId} / {@code songName} / {@code artistName}
 * / {@code album} / {@code duration} / {@code coverUrl} / {@code audioUrl}），
 * 前端可以复用同一套歌曲渲染逻辑，只是多了 {@code lastPlayedAt} 与 {@code playCount}。
 *
 * <p><b>不外泄播放进度</b>：{@code tb_play_history.last_position_seconds} 只用于留痕与后续续播，
 * 不属于列表展示需要，因此这里没有对应字段。
 */
@Data
public class PlayHistoryVO implements Serializable, SongArtistAware {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 历史记录 id（删除单条时使用；不含任何用户标识）
     */
    private Long historyId;

    /**
     * 歌曲 id
     */
    private Long songId;

    /**
     * 专辑 id，供专辑名做成可跳转链接；单曲可能为 null
     */
    private Long albumId;

    /**
     * 歌名
     */
    private String songName;

    /**
     * 歌手展示串（多歌手时为「银临 / Aki阿杰」）；结构化列表见 {@link #artists}
     */
    private String artistName;

    /**
     * 结构化歌手列表，按 display_order 升序；无关系时为空列表
     */
    private List<ArtistNameVO> artists;

    /**
     * 专辑名
     */
    private String album;

    /**
     * 歌曲时长
     */
    private String duration;

    /**
     * 歌曲封面 url
     */
    private String coverUrl;

    /**
     * 歌曲 url
     */
    private String audioUrl;

    /**
     * 歌曲发行时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate releaseTime;

    /**
     * 最近一次有效播放时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastPlayedAt;

    /**
     * 累计有效播放次数
     */
    private Integer playCount;

}
