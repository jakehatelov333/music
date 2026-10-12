package cn.edu.seig.vibemusic.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 管理员歌单查询条件。
 * <p>
 * 单独定义而不是复用 {@link PlaylistDTO}，避免公开接口通过请求参数过滤发布状态或来源。
 * 发布状态（status）与来源（source）可以任意组合；来源用于在「平台歌单」与「用户歌单」之间切换。
 */
@Data
public class AdminPlaylistQueryDTO implements Serializable {

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

    /** 歌单名称，模糊匹配 */
    @Size(max = 200)
    private String title;

    /** 歌单风格，模糊匹配（风格支持自定义，因此不做枚举校验） */
    @Size(max = 200)
    private String style;

    /** DRAFT / PUBLISHED；为空表示不限制 */
    @Size(max = 16)
    private String status;

    /** PLATFORM=仅平台歌单，USER=仅用户歌单；为空表示全部 */
    @Size(max = 16)
    private String source;

}
