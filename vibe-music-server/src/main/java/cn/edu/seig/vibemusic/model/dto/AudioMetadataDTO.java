package cn.edu.seig.vibemusic.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 音频元数据解析结果，仅供歌曲上传工作流内部使用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AudioMetadataDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String title;
    private String artist;
    private String album;
    private Integer year;
    private Double durationSeconds;
    private String durationText;
    private boolean durationParsed;
    private boolean metadataAvailable;
    private String audioFormat;
    private Integer sampleRate;
    private Long bitRate;
    private boolean hasEmbeddedCover;
    private byte[] embeddedCover;
    private String embeddedCoverMimeType;
    private Integer trackNumber;
    private Integer discNumber;
}
