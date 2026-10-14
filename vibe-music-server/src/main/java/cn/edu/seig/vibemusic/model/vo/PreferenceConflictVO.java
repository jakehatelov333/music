package cn.edu.seig.vibemusic.model.vo;

/**
 * 喜欢与排除同时命中时的冲突提示（排除优先）。
 *
 * @param targetType  ARTIST / STYLE
 * @param targetId    歌手 id，风格为 null
 * @param targetValue 歌手名 / 风格名
 * @param message     人类可读的冲突说明
 */
public record PreferenceConflictVO(String targetType, Long targetId, String targetValue, String message) {
}
