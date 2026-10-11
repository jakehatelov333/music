package cn.edu.seig.vibemusic.model.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * 歌曲正式保存后的结果。
 */
@Data
public class SongUploadCommitVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long songId;
    private String songName;
    private Long artistId;
    private String artistName;
    private boolean artistCreated;
    private String album;
    private String duration;
    private String coverUrl;
    private String audioUrl;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate releaseTime;
}
