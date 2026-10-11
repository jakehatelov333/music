package cn.edu.seig.vibemusic.model.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

@Data
public class SongVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 歌曲 id
     */
    private Long songId;

    /**
     * 专辑 id，单曲为 null
     */
    private Long albumId;

    /**
     * 歌名
     */
    private String songName;

    /**
     * 歌手
     */
    private String artistName;

    /**
     * 专辑
     */
    private String album;

    /**
     * 唱片序号
     */
    private Integer discNumber;

    /**
     * 曲序
     */
    private Integer trackNumber;

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
     * 喜欢状态
     * 0：默认
     * 1：喜欢
     */
    private Integer likeStatus;

    /**
     * 歌词状态：0 未获取 / 1 已获取 / 2 手动录入 / 3 无匹配
     * 供管理端专辑详情展示补全状态
     */
    private Integer lyricStatus;

    /**
     * 歌曲发行时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate releaseTime;

}
