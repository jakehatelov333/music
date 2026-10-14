package cn.edu.seig.vibemusic.model.vo;

import java.util.List;

/**
 * 运行时合并出的最终生效偏好（不落库）。
 *
 * <p>合并规则（优先级高 → 低）：当前请求约束 &gt; 手动排除条件 &gt; 手动喜欢项
 * &gt; 自动画像补充。排除优先：同一歌手 / 风格同时被喜欢与排除时，
 * 从 like 列表移除并记入 {@code conflicts}（见 MemoryOverviewVO）。
 *
 * @param likeArtists     喜欢的歌手（优先考虑，非强制）
 * @param likeStyles      喜欢的风格（优先考虑，非强制）
 * @param preferenceNotes 偏好要求（自由文本）
 * @param excludeArtists  排除的歌手（硬约束）
 * @param excludeStyles   排除的风格（硬约束）
 * @param excludeNotes    排除条件（自由文本，硬约束）
 */
public record EffectivePreferenceVO(
        List<EffectivePreferenceItemVO> likeArtists,
        List<EffectivePreferenceItemVO> likeStyles,
        List<EffectivePreferenceItemVO> preferenceNotes,
        List<EffectivePreferenceItemVO> excludeArtists,
        List<EffectivePreferenceItemVO> excludeStyles,
        List<EffectivePreferenceItemVO> excludeNotes) {
}
