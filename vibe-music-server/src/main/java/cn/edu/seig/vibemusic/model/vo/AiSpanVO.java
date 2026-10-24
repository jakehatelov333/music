package cn.edu.seig.vibemusic.model.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 执行树节点。{@code children} 按真实父子关系组装，不按时间顺序推测。 */
@Data
public class AiSpanVO {

    private String spanId;
    private String parentSpanId;
    private String spanType;
    private String name;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Long durationMs;
    private String model;
    private Long inputTokens;
    private Long outputTokens;
    private String tokenSource;
    private String errorCategory;
    private String errorMessage;
    /** 解析后的属性（提示词版本、注入块状态、护栏结论等）。 */
    private Object attributes;
    /** 该节点是否有独立明细可查（MODEL / TOOL）。 */
    private Boolean hasDetail;

    private List<AiSpanVO> children = new ArrayList<>();
}
