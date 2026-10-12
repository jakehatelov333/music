package cn.edu.seig.vibemusic.enumeration;

import lombok.Getter;

/**
 * 歌单发布状态。
 * <p>
 * 发布状态与可见性（{@code is_public}）是两个独立维度：
 * 草稿无论公开与否都不对普通用户开放，已发布歌单再按可见性决定是否出现在公开列表中。
 */
@Getter
public enum PlaylistStatusEnum {

    /** 草稿：仅所有者与管理员可见 */
    DRAFT("DRAFT"),
    /** 已发布：进入可见性控制流程 */
    PUBLISHED("PUBLISHED");

    private final String status;

    PlaylistStatusEnum(String status) {
        this.status = status;
    }

    /**
     * 宽松解析，避免存量数据或前端大小写差异导致 500。
     *
     * @param value 请求值
     * @return 匹配的枚举，无法识别时返回 null
     */
    public static PlaylistStatusEnum parse(String value) {
        if (value == null || value.isBlank()) return null;
        for (PlaylistStatusEnum item : values()) {
            if (item.status.equalsIgnoreCase(value.trim())) return item;
        }
        return null;
    }

}
