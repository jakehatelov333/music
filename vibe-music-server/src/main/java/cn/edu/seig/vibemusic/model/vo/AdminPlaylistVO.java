package cn.edu.seig.vibemusic.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 管理员歌单列表项：在歌单基本信息之外带上歌曲数量、封面来源与所有者，
 * 便于后台表格直接展示而无需再查一次明细。
 * <p>
 * 列表同时包含平台歌单（{@code ownerUserId} 为空）与用户自建歌单，
 * 用 {@code ownerName} 区分来源，避免管理员"搜不到某个歌单"。
 */
@Data
public class AdminPlaylistVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long playlistId;

    private String title;

    private String coverUrl;

    private String introduction;

    private String style;

    private Boolean isPublic;

    /** DRAFT / PUBLISHED */
    private String status;

    private Boolean coverIsCustom;

    private Long songCount;

    /** 为空表示平台歌单 */
    private Long ownerUserId;

    /** 平台歌单为 null，用户歌单为创建者用户名 */
    private String ownerName;

}
