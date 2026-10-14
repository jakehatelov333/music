package cn.edu.seig.vibemusic.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 个性化推荐开关请求体。 */
@Data
public class MemoryPersonalizationRequest {

    @NotNull(message = "enabled 不能为空")
    private Boolean enabled;
}
