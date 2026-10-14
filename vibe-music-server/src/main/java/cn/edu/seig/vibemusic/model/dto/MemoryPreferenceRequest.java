package cn.edu.seig.vibemusic.model.dto;

import lombok.Data;

/**
 * 新增 / 修改手动偏好的请求体。
 *
 * <p>{@code category} 与 {@code targetId}/{@code targetValue} 的条件必填关系
 * （歌手类要 id、风格/文本类要 value）无法用单个注解表达，统一在
 * {@code UserMemoryPreferenceService} 里按 category 校验并转成 400。
 */
@Data
public class MemoryPreferenceRequest {

    /** {@code MemoryPreferenceCategory} 的枚举名。 */
    private String category;

    /** 歌手类必填（artist_id）；风格 / 自由文本类忽略。 */
    private Long targetId;

    /** 风格 / 自由文本类必填；歌手类忽略（由后端按 id 解析权威歌手名）。 */
    private String targetValue;
}
