package cn.edu.seig.vibemusic.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class ArtistDTO implements Serializable {

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
     * 歌手姓名
     */
    @Size(max = 200)
    private String artistName;

    /**
     * 歌手性别：0-男，1-女
     */
    private Integer gender;

    /**
     * 歌手所处地区
     */
    @Size(max = 200)
    private String area;

    /**
     * 是否包含已停用（软删除）的歌手。
     * 仅管理端列表使用；用户端、选择器、搜索一律只看启用记录。
     * 为 null / false 时只返回启用记录。
     */
    private Boolean includeDisabled;

}
