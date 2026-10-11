package cn.edu.seig.vibemusic.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 音频解析后返回给前端的歌曲信息。
 */
@Data
public class SongUploadMetadataVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String title;
    private String artist;
    private String album;
    private Integer year;
    private Double duration;
    private String durationText;
    private boolean durationParsed;
    private boolean metadataAvailable;
    private boolean hasEmbeddedCover;
    private String coverUrl;
    private String coverSource;
    private String audioFormat;
    private Integer sampleRate;
    private Long bitRate;
    private Long fileSize;
}
