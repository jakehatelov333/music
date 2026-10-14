package cn.edu.seig.vibemusic.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class SongAndArtistDTO implements Serializable {

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
     * 歌手
     */
    private Long artistId;

    /**
     * 歌曲名
     */
    @Size(max = 200)
    private String songName;

    /**
     * 歌手名，模糊匹配；用于不依赖歌手树时直接按名字筛选
     */
    @Size(max = 200)
    private String artistName;

    /**
     * 专辑
     */
    @Size(max = 200)
    private String album;

    /** 仅查询歌词文本为空且未被标记为伴奏/无需歌词的歌曲 */
    private Boolean onlyMissingLyric;

    /**
     * 排序字段（白名单校验在 Service 层）：
     * songId / songName / releaseTime；null 时按默认排序（发行日期倒序）。
     */
    @Size(max = 32)
    private String sortField;

    /**
     * 排序方向：asc / desc；null 时按默认排序。
     */
    @Size(max = 8)
    private String order;

}
