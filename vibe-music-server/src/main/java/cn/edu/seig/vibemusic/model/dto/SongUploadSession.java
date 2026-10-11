package cn.edu.seig.vibemusic.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 保存在 Redis 中的歌曲上传临时会话。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SongUploadSession implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String uploadId;
    private long createdAt;
    private String audioObjectName;
    private String audioUrl;
    private String audioOriginalName;
    private String audioContentType;
    private long audioSize;
    private String audioFormat;
    private Double durationSeconds;
    private String durationText;
    private boolean durationParsed;
    private String title;
    private String artist;
    private String album;
    private Integer year;
    private boolean metadataAvailable;
    private boolean hasEmbeddedCover;
    private String coverObjectName;
    private String coverUrl;
    private String coverSource;
    private String lyricsObjectName;
    private String lyricsUrl;
    private String lyricsContent;
    private Integer trackNumber;
    private Integer discNumber;
}
