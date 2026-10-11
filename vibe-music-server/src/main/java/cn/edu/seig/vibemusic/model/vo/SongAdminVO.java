package cn.edu.seig.vibemusic.model.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

@Data
public class SongAdminVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 歌曲 id
     */
    private Long songId;

    private Long albumId;

    /**
     * 歌手 id
     */
    private Long artistId;

    /**
     * 歌手
     */
    private String artistName;

    /**
     * 歌名
     */
    private String songName;

    /**
     * 专辑
     */
    private String album;

    private Integer discNumber;

    private Integer trackNumber;

    /**
     * 歌词
     */
    private String lyric;

    /**
     * 歌词来源：QQ_MUSIC / LRCLIB / MANUAL
     */
    private String lyricSource;

    /**
     * 歌词状态：0 未获取 / 1 已获取 / 2 手动录入 / 3 无匹配
     */
    private Integer lyricStatus;

    /**
     * 歌曲时长
     */
    private String duration;

    /**
     * 歌曲风格
     */
    private String style;

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

}
