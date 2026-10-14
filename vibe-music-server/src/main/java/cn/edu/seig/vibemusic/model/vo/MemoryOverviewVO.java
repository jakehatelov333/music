package cn.edu.seig.vibemusic.model.vo;

import java.util.List;

/**
 * {@code GET /ai/memory/overview} 的响应体。
 *
 * @param personalizationEnabled 个性化推荐开关（无设置行 = true）
 * @param preferences            手动偏好全集（关闭开关时仍返回 —— 关闭不等于删除）
 * @param effective              运行时合并的最终生效偏好；开关关闭时全部为空列表
 * @param conflicts              喜欢与排除同时命中的冲突（排除优先）
 */
public record MemoryOverviewVO(
        boolean personalizationEnabled,
        List<MemoryPreferenceVO> preferences,
        EffectivePreferenceVO effective,
        List<PreferenceConflictVO> conflicts) {
}
