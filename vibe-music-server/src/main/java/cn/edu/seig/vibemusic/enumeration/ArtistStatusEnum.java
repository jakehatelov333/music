package cn.edu.seig.vibemusic.enumeration;

import lombok.Getter;

/**
 * 歌手状态（软删除标记）。
 *
 * <p>为什么不物理删除：{@code tb_song.artist_id} 的外键是 {@code ON DELETE CASCADE}，
 * 删掉一位歌手会连带删掉他/她的全部歌曲；{@code tb_album.artist_id} 也在引用。
 * 因此「合并掉的组合歌手」（例如 {@code 银临/Aki阿杰}）只能停用，不能删除。
 *
 * <p>{@code DISABLED} 的记录对正常业务不可见（列表、选择器、搜索、随机、画像、上传匹配），
 * 但管理端可以通过 {@code includeDisabled} 显式查看，用于追溯历史。
 */
@Getter
public enum ArtistStatusEnum {

    /** 启用：参与一切正常业务 */
    ACTIVE("ACTIVE"),
    /** 停用（软删除）：默认对所有查询不可见 */
    DISABLED("DISABLED");

    private final String status;

    ArtistStatusEnum(String status) {
        this.status = status;
    }

    /**
     * 宽松解析，避免存量数据或前端大小写差异导致异常。
     *
     * @param value 请求值
     * @return 匹配的枚举，无法识别时返回 null
     */
    public static ArtistStatusEnum parse(String value) {
        if (value == null || value.isBlank()) return null;
        for (ArtistStatusEnum item : values()) {
            if (item.status.equalsIgnoreCase(value.trim())) return item;
        }
        return null;
    }

    /**
     * 是否处于启用状态。
     *
     * <p>null / 空白一律按<b>启用</b>处理：迁移脚本执行前的历史行没有状态值，
     * 把它们误判成停用会导致歌手集体消失。
     *
     * @param value 数据库中的状态值
     * @return true 表示启用
     */
    public static boolean isActive(String value) {
        return value == null || value.isBlank() || ACTIVE.status.equalsIgnoreCase(value.trim());
    }

}
