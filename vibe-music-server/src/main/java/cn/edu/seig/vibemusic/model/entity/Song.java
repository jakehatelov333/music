package cn.edu.seig.vibemusic.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * <p>
 * 
 * </p>
 *
 * @author sunpingli
 * @since 2025-01-09
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_song")
public class Song implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 歌曲 id
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long songId;

    /**
     * 歌手 id
     */
    @TableField("artist_id")
    private Long artistId;

    /**
     * 专辑 id，单曲为 null
     */
    @TableField("album_id")
    private Long albumId;

    /**
     * 歌名
     */
    @TableField("name")
    private String songName;

    /**
     * 专辑
     */
    @TableField("album")
    private String album;

    /**
     * 唱片序号
     */
    @TableField("disc_number")
    private Integer discNumber;

    /**
     * 曲序
     */
    @TableField("track_number")
    private Integer trackNumber;

    /**
     * 歌词
     */
    @TableField("lyric")
    private String lyric;

    /**
     * 歌词来源：QQ_MUSIC / LRCLIB / MANUAL
     */
    @TableField("lyric_source")
    private String lyricSource;

    /**
     * 歌词状态：0 未获取 / 1 已获取 / 2 手动录入 / 3 无匹配 / 4 伴奏或无需歌词
     */
    @TableField("lyric_status")
    private Integer lyricStatus;

    /**
     * 歌词更新时间
     */
    @TableField("lyric_updated_at")
    private LocalDateTime lyricUpdatedAt;

    /**
     * 歌曲时长
     */
    @TableField("duration")
    private String duration;

    /**
     * 歌曲风格
     */
    @TableField("style")
    private String style;

    /**
     * 歌曲封面 url
     */
    @TableField("cover_url")
    private String coverUrl;

    /**
     * 歌曲 url
     */
    @TableField("audio_url")
    private String audioUrl;

    /**
     * 歌曲发行时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @TableField("release_time")
    private LocalDate releaseTime;

}
