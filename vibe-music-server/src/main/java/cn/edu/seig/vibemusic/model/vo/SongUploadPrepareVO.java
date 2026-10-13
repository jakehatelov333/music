package cn.edu.seig.vibemusic.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 上传准备阶段返回结果。
 */
@Data
public class SongUploadPrepareVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String uploadId;
    private SongUploadMetadataVO metadata;
    private SongUploadFilesVO files;
    private Long matchedArtistId;
    private String matchedArtistName;
    private boolean artistMatched;
    /**
     * 歌手字段的解析结果（是否疑似合作歌手、拆分候选与匹配情况）。
     * 管理端据此在上传预览里展示最终确认的歌手列表。
     */
    private ArtistParsingVO artistParsing;
    private String warning;
}
