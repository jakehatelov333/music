package cn.edu.seig.vibemusic.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 当前上传会话关联的临时文件地址。
 */
@Data
public class SongUploadFilesVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String audioUrl;
    private String coverUrl;
    private String lyricsUrl;
    private String lyricsContent;
}
