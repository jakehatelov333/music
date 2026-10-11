package cn.edu.seig.vibemusic.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

@Data
public class SongUpdateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 歌曲 id
     */
    private Long songId;

    /**
     * 歌手 id
     */
    private Long artistId;

    /**
     * 歌名
     */
    private String songName;

    /**
     * 专辑
     */
    private String album;

    /**
     * 专辑 id，可空；为空时按专辑名自动匹配
     */
    private Long albumId;

    /**
     * 当前专辑不存在时，是否已由管理端确认创建
     */
    private Boolean confirmCreateAlbum;

    /**
     * 唱片序号
     */
    private Integer discNumber;

    /**
     * 曲序
     */
    private Integer trackNumber;

    /**
     * 歌曲风格
     */
    private String style;

    /**
     * 歌词（LRC 文本），管理端可手动编辑
     */
    private String lyric;

    /** 是否已确认该曲目为伴奏或无需歌词 */
    private Boolean instrumental;

    /**
     * 歌曲发行时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate releaseTime;

}
