package cn.edu.seig.vibemusic.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class FeedbackDTO implements Serializable {

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
     * 反馈内容关键字
     */
    @Size(max = 200)
    private String keyword;

    /**
     * 查看状态：0-未查看，1-已查看；null 表示全部
     */
    @Min(0)
    @Max(1)
    private Integer isRead;

    /**
     * 排序字段（白名单校验在 Service 层）：
     * feedbackId / userId / createTime；null 时按默认排序（反馈时间倒序）。
     */
    @Size(max = 32)
    private String sortField;

    /**
     * 排序方向：asc / desc；null 时按默认排序。
     */
    @Size(max = 8)
    private String order;

}
