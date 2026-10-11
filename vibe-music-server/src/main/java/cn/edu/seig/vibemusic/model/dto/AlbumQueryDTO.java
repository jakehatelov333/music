package cn.edu.seig.vibemusic.model.dto;

import jakarta.validation.constraints.NotNull;
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
    private Integer pageNum;

    @NotNull
    private Integer pageSize;

    /**
     * 专辑名称关键字。
     */
    private String albumName;

    /** 专辑或主艺人名称关键字。 */
    private String keyword;

    /**
     * 主艺人 id，用于艺人详情页筛选专辑。
     */
    private Long artistId;

    /** 管理端状态筛选；用户端固定只查询 PUBLISHED。 */
    private String status;
}
