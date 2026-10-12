package cn.edu.seig.vibemusic.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class PlaylistVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 歌单 id
     */
    private Long playlistId;

    /**
     * 歌单标题
     */
    private String title;

    /**
     * 歌单封面
     */
    private String coverUrl;

    private Long ownerUserId;
    private Boolean isPublic;
    private String introduction;
    private String style;
    private Long songCount;

    /** DRAFT / PUBLISHED */
    private String status;

    /** True when the cover was uploaded by hand instead of derived from the first song. */
    private Boolean coverIsCustom;

    /** 公开列表专用：当前登录用户是否是该歌单的创建者 */
    private Boolean owned;

    /** 公开列表专用：当前登录用户是否已收藏该歌单 */
    private Boolean favorite;

}
