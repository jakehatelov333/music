package cn.edu.seig.vibemusic.model.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

/**
 * 歌曲正式保存后的结果。
 */
@Data
public class SongUploadCommitVO implements Serializable, SongArtistAware {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long songId;
    private String songName;
    /** 主艺人 id（display_order 最小者）；兼容字段。 */
    private Long artistId;
    /** 多歌手展示串；兼容字段。 */
    private String artistName;
    /** 结构化歌手列表，按 display_order 升序；无关系时为空列表。 */
    private List<ArtistNameVO> artists;
    private boolean artistCreated;
    private String album;
    private String duration;
    private String coverUrl;
    private String audioUrl;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate releaseTime;
}
