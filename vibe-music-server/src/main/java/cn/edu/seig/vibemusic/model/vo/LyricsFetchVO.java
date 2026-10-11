package cn.edu.seig.vibemusic.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 单曲歌词抓取结果（预览或保存后返回）。
 */
@Data
public class LyricsFetchVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long songId;

    private String songName;

    private String artistName;

    private String album;

    /** 是否匹配到歌词 */
    private Boolean matched;

    /** 来源：QQ_MUSIC / LRCLIB */
    private String source;

    /** 匹配得分，便于管理员判断可信度 */
    private Double score;

    /** 清洗后的 LRC 文本 */
    private String lyric;

    /** 结果说明，例如"未找到匹配的歌词" */
    private String message;

    /** 该歌曲是否已有歌词（用于提示是否会被覆盖） */
    private Boolean hasExistingLyric;

}
