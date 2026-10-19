package cn.edu.seig.vibemusic.model.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 按元数据搜索歌词的请求。
 *
 * <p>与 {@code preview(songId)} 的区别：上传流程里歌曲<b>还没有入库</b>，没有 songId，
 * 只有表单里填的歌名 / 歌手 / 专辑 / 时长。因此这里直接接收这些字段，
 * 组装成 {@code LyricsProvider.Query} 走同一套数据源与打分逻辑。
 */
@Data
public class LyricsSearchDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 歌名（必填） */
    private String songName;

    /** 歌手展示串，例如「银临 / Aki阿杰」 */
    private String artistName;

    /** 专辑名，可为空 */
    private String album;

    /** 音频时长（秒），可为空；用于版本消歧（同名不同版本） */
    private Double durationSeconds;
}
