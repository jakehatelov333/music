package cn.edu.seig.vibemusic.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

@Data
public class SongAddDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 歌手 id（单个，兼容旧客户端）
     * artistIds 非空时以 artistIds 为准；否则回退为「只含本字段的单元素列表」。
     */
    private Long artistId;

    /**
     * 演唱歌手 id 列表，<b>顺序即展示顺序</b>（第一个为主艺人）。
     * 留空表示沿用旧的单歌手字段 artistId。
     */
    private List<Long> artistIds;

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

    /** 是否已确认该曲目为伴奏或无需歌词 */
    private Boolean instrumental;

    /**
     * 歌曲发行时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate releaseTime;

}
