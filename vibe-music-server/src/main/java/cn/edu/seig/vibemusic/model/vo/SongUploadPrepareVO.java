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
    private String warning;
}
