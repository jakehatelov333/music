package cn.edu.seig.vibemusic.enumeration;

/**
 * 最终生效偏好里单项的来源。
 *
 * <p>{@code MANUAL} 表示来自用户手动设置（优先级更高），{@code AUTO} 表示来自
 * 系统推断的音乐画像（只补充用户未明确指定的维度）。前端据此展示
 * 「来自你的设置」/「系统推断」，不把两层混写进同一个可覆盖字段。
 */
public enum PreferenceSource {
    MANUAL,
    AUTO
}
