package cn.edu.seig.vibemusic.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 播放历史分页查询条件。
 *
 * <p><b>刻意没有 userId 字段</b>：归属只来自服务端认证上下文
 * （{@code PlaylistAccessService.requireUserId()}）。让客户端传 userId 等于
 * 开放「读别人历史」的入口，参数一旦被接受就很难再收回。
 */
@Data
public class PlayHistoryQueryDTO implements Serializable {

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
     * 歌名关键词（可选，模糊匹配）
     */
    @Size(max = 200)
    private String songName;

}
