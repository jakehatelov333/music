package cn.edu.seig.vibemusic.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

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

    private Long artistId;

    private String artistName;

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
