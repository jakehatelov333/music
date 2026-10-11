package cn.edu.seig.vibemusic.model.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 专辑列表项。
 */
@Data
public class AlbumVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long albumId;

    private Long artistId;

    private String albumName;

    private String artistName;

    private String coverUrl;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate releaseDate;

    private String description;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Long songCount;
}
