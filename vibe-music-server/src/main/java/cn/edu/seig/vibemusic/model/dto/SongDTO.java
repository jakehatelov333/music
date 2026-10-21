package cn.edu.seig.vibemusic.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class SongDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 页码
     */
    @NotNull
    @Min(1)
    @Max(100000)
    private Integer pageNum;

    /**
     * 每页数量
     */
    @NotNull
    @Min(1)
    @Max(100)
    private Integer pageSize;

    /**
     * 歌曲名
     */
    @Size(max = 200)
    private String songName;

    /**
     * 歌手
     */
    @Size(max = 200)
    private String artistName;

    /**
     * 专辑
     */
    @Size(max = 200)
    private String album;

    /**
     * 统一关键词：同时匹配歌曲名或歌手名。
     *
     * <p>收藏页的搜索框只有一个输入框，用户输入“周杰伦”时既可能是歌名，
     * 也可能是歌手名。这个字段与 songName / artistName 的“同时满足”语义
     * 分开，避免把两个条件拼成 AND。
     */
    @Size(max = 200)
    private String keyword;

}
