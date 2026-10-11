package cn.edu.seig.vibemusic.model.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 保存歌词请求（管理端手动编辑或预览确认后提交）。
 */
@Data
public class LyricsSaveDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 歌词内容（LRC 文本）；为空表示清空该歌曲歌词 */
    private String lyric;

    /** 是否覆盖已有歌词，默认 false——保护手工录入与已确认的内容 */
    private Boolean force;

}
