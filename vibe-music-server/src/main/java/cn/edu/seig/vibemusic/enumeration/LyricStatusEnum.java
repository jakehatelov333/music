package cn.edu.seig.vibemusic.enumeration;

import lombok.Getter;

/**
 * 歌词状态。
 * <p>
 * 用于记录每首歌的歌词获取情况，其中 {@link #NO_MATCH} 作为"负缓存"，
 * 避免对确实没有歌词的纯音乐反复发起抓取请求。
 */
@Getter
public enum LyricStatusEnum {

    /** 尚未尝试获取 */
    MISSING(0, "未获取"),
    /** 已自动获取并入库 */
    FETCHED(1, "已获取"),
    /** 管理员手动录入或上传 */
    MANUAL(2, "手动录入"),
    /** 已尝试抓取但所有数据源均无匹配 */
    NO_MATCH(3, "无匹配");

    private final Integer id;
    private final String lyricStatus;

    LyricStatusEnum(Integer id, String lyricStatus) {
        this.id = id;
        this.lyricStatus = lyricStatus;
    }

}
