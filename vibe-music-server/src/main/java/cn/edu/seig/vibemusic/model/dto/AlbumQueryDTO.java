package cn.edu.seig.vibemusic.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 用户端专辑列表查询参数。
 */
@Data
public class AlbumQueryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull
    @Min(1)
    @Max(100000)
    private Integer pageNum;

    @NotNull
    @Min(1)
    @Max(100)
    private Integer pageSize;

    /**
     * 专辑名称关键字。
     */
    @Size(max = 200)
    private String albumName;

    /** 专辑或主艺人名称关键字。 */
    @Size(max = 200)
    private String keyword;

    /**
     * 主艺人 id，用于艺人详情页筛选专辑。
     */
    private Long artistId;

    /** 管理端状态筛选；用户端固定只查询 PUBLISHED。 */
    @Size(max = 200)
    private String status;

    /**
     * 排序字段（白名单校验在 Service 层）：
     * albumId / albumName / releaseDate / createdAt / songCount；null 时按默认排序（发行日期倒序）。
     */
    @Size(max = 32)
    private String sortField;

    /**
     * 排序方向：asc / desc；null 时按默认排序。
     */
    @Size(max = 8)
    private String order;

    /**
     * 是否在结果中排除主艺人已停用（软删除）的专辑。
     * 客户端列表置 true；管理端列表不设置（保持可见全部专辑）。
     */
    private Boolean excludeDisabledArtist;
}
