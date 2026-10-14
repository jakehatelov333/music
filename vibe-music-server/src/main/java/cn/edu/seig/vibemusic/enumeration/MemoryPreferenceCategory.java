package cn.edu.seig.vibemusic.enumeration;

import java.util.Locale;
import java.util.Optional;

/**
 * 用户手动偏好的分类。
 *
 * <p><b>为什么不用一个布尔字段区分喜欢/排除：</b>「喜欢歌手」与「排除歌手」的
 * 结构化要求相同（都要 {@code targetId}），但语义相反；把它们拆成不同常量后，
 * 合并逻辑（排除优先）只需按 {@link #isExclusion()} 分流，不需要在字符串里猜语义。
 *
 * <p>分类决定了校验规则：歌手类要解析出权威歌手名，风格类要落在派生风格集合内，
 * 自由文本类只做长度约束。见 {@code UserMemoryPreferenceService#normalize}。
 */
public enum MemoryPreferenceCategory {

    /** 喜欢的歌手：targetId 必填（artist_id），targetValue 由后端按 id 解析。 */
    FAVORITE_ARTIST("喜欢的歌手"),
    /** 喜欢的风格：targetValue 必填，须属于派生风格集合。 */
    FAVORITE_STYLE("喜欢的风格"),
    /** 偏好要求（自由文本）：1..200 字。 */
    PREFERENCE_NOTE("偏好要求"),
    /** 排除歌手：同 FAVORITE_ARTIST。 */
    EXCLUDE_ARTIST("排除歌手"),
    /** 排除风格：同 FAVORITE_STYLE。 */
    EXCLUDE_STYLE("排除风格"),
    /** 排除条件（自由文本）：同 PREFERENCE_NOTE。 */
    EXCLUDE_NOTE("排除条件");

    private final String description;

    MemoryPreferenceCategory(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /** 是否歌手类（需要 targetId 并解析权威歌手名）。 */
    public boolean isArtist() {
        return this == FAVORITE_ARTIST || this == EXCLUDE_ARTIST;
    }

    /** 是否风格类（需要 targetValue 且须属于派生风格集合）。 */
    public boolean isStyle() {
        return this == FAVORITE_STYLE || this == EXCLUDE_STYLE;
    }

    /** 是否自由文本类（targetValue 长度 1..200）。 */
    public boolean isNote() {
        return this == PREFERENCE_NOTE || this == EXCLUDE_NOTE;
    }

    /** 是否排除类。合并规则里排除优先于喜欢。 */
    public boolean isExclusion() {
        return this == EXCLUDE_ARTIST || this == EXCLUDE_STYLE || this == EXCLUDE_NOTE;
    }

    /**
     * 宽松解析：忽略大小写与首尾空白，无法识别时返回 {@link Optional#empty()}。
     * 非法值不抛异常，方便调用方按自己的语义转成 400 或忽略。
     */
    public static Optional<MemoryPreferenceCategory> parse(String value) {
        if (value == null) return Optional.empty();
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        for (MemoryPreferenceCategory item : values()) {
            if (item.name().equals(normalized)) return Optional.of(item);
        }
        return Optional.empty();
    }

    /**
     * 严格解析：非法值抛 {@link IllegalArgumentException}。
     * 调用方通常捕获后转成 400，避免把内部枚举名泄漏给前端。
     */
    public static MemoryPreferenceCategory require(String value) {
        return parse(value).orElseThrow(() -> new IllegalArgumentException("未知的偏好分类"));
    }
}
