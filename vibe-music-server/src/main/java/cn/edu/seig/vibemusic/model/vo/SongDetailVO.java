package cn.edu.seig.vibemusic.model.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

@Data
public class SongDetailVO implements Serializable, SongArtistAware {

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
     * 多歌手时的展示串（按 display_order 用 " / " 拼接）；兼容字段。
     */
    private String artistName;

    /**
     * 结构化歌手列表，按 display_order 升序；与 {@link #artistName} 同源派生。无关系时为空列表。
     */
    private List<ArtistNameVO> artists;

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
     * 歌词
     */
    private String lyric;

    /** 歌词状态：4 表示已确认是伴奏或无需歌词 */
    private Integer lyricStatus;

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
     * 喜欢状态
     * 0：默认
     * 1：喜欢
     */
    private Integer likeStatus;

    /**
     * 评论列表
     */
    private List<CommentVO> comments;

}
