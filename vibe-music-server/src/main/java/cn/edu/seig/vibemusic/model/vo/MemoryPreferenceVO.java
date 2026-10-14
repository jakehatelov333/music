package cn.edu.seig.vibemusic.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 一条手动偏好的对外视图（字段与契约 §5.1 一致）。 */
@Data
public class MemoryPreferenceVO {

    private Long id;
    private String category;
    private Long targetId;
    private String targetValue;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
