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
    /**
     * 会话的绝对过期时间（毫秒时间戳）。
     *
     * <p>只有「读取会话」接口（{@code GET /admin/song-upload/{uploadId}}）会填，
     * 草稿箱据此展示「x 小时后过期」。其它接口为 null —— 前端不要拿它当必填字段。
     */
    private Long expiresAt;
}
