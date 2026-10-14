package cn.edu.seig.vibemusic.model.dto;

import cn.edu.seig.vibemusic.enumeration.BannerStatusEnum;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class BannerDTO implements Serializable {

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
     * 轮播图状态：0-启用，1-禁用
     */
    private BannerStatusEnum bannerStatus;

    /**
     * 排序字段（白名单校验在 Service 层）：bannerId；null 时按默认排序（编号倒序）。
     */
    @Size(max = 32)
    private String sortField;

    /**
     * 排序方向：asc / desc；null 时按默认排序。
     */
    @Size(max = 8)
    private String order;

}
