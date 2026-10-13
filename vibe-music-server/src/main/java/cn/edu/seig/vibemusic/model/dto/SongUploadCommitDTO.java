package cn.edu.seig.vibemusic.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

/**
 * 歌曲上传最终确认参数。
 */
@Data
public class SongUploadCommitDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "uploadId不能为空")
    private String uploadId;

    @NotBlank(message = "歌曲名不能为空")
    private String songName;

    /** 歌手 id（单个，兼容旧客户端）。artistIds 非空时以 artistIds 为准。 */
    private Long artistId;

    /** 歌手名（单个，兼容旧客户端）。artistIds 非空时忽略。 */
    private String artistName;

    /**
     * 演唱歌手 id 列表，<b>顺序即展示顺序</b>（第一个为主艺人）。
     * 由上传预览的人工确认结果产出；留空时回退到 artistId / artistName。
     */
    private List<Long> artistIds;

    private String album;

    private Long albumId;

    private Integer discNumber;

    private Integer trackNumber;

    @Min(value = 1900, message = "发行年份不能早于1900年")
    @Max(value = 2100, message = "发行年份不合法")
    private Integer year;

    /**
     * 完整发行日期，优先于兼容字段 year
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate releaseDate;

    private String style;

    private String lyrics;
}
