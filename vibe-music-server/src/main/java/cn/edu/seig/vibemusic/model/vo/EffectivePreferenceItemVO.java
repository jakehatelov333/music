package cn.edu.seig.vibemusic.model.vo;

import cn.edu.seig.vibemusic.enumeration.PreferenceSource;

/**
 * 最终生效偏好里的单项。
 *
 * @param value    展示与注入用文本（歌手名 / 风格名 / 自由文本）
 * @param targetId 歌手类为 artist_id，其余为 null
 * @param source   MANUAL=用户设置，AUTO=系统推断
 */
public record EffectivePreferenceItemVO(String value, Long targetId, PreferenceSource source) {
}
