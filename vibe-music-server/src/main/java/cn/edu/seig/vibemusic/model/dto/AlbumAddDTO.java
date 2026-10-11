package cn.edu.seig.vibemusic.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * 管理端新增专辑参数。
 */
@Data
public class AlbumAddDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long artistId;

    private String albumName;

    private String coverUrl;

    /** DRAFT/PUBLISHED，默认 DRAFT。 */
    private String status;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate releaseDate;

    private String description;
}
