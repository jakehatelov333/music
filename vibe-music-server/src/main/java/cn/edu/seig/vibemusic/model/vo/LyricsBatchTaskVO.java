package cn.edu.seig.vibemusic.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量补全任务状态。
 */
@Data
public class LyricsBatchTaskVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String taskId;

    /** RUNNING / DONE */
    private String status;

    /** 任务描述，例如"补全专辑《太阳之子》" */
    private String title;

    /** 待处理总数 */
    private Integer total;

    /** 已处理数量 */
    private Integer done;

    /** 成功补全数量 */
    private Integer success;

    /** 数据源均无匹配的数量 */
    private Integer noMatch;

    /** 因已有歌词（且未强制覆盖）而跳过的数量 */
    private Integer skipped;

    /** 处理失败数量 */
    private Integer failed;

    /** 失败明细（歌曲名 + 原因），最多保留若干条 */
    private List<String> failures = new ArrayList<>();

    private Long startedAt;

    private Long finishedAt;

    /** 进度百分比，0-100 */
    public Integer getProgress() {
        if (total == null || total <= 0 || done == null) {
            return 0;
        }
        return (int) Math.round(done * 100d / total);
    }

}
