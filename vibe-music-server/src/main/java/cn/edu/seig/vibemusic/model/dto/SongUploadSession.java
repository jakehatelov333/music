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
    /**
     * 最后一次活动时间（毫秒）。每次 {@code saveSession} 都会刷新。
     *
     * <p>暂存文件的清理判据用的是它，不是 {@link #createdAt}：用户可能在上传后
     * 隔很久才回来补充封面或歌词，只要还在操作就不该被清理。
     * 字段为 0 表示来自旧会话（该字段引入前写入），读取方按 {@code createdAt} 兜底。
     */
    private long lastActivityAt;
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
