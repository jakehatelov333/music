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
     * 状态筛选（仅管理端列表使用）：
     * ACTIVE=只看启用中，DISABLED=只看已停用（软删除），null/空=全部。
     * 用户端、选择器、搜索一律只看启用记录，与这里无关。
     */
    @Size(max = 16)
    private String status;

    /**
     * 排序字段（白名单校验在 Service 层）：
     * artistId / artistName / birth；null 时按默认排序（编号倒序）。
     */
    @Size(max = 32)
    private String sortField;

    /**
     * 排序方向：asc / desc；null 时按默认排序。
     */
    @Size(max = 8)
    private String order;

}
