package cn.edu.seig.vibemusic.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class PlaylistDetailVO implements Serializable {

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

    /**
     * 歌单简介
     */
    private String introduction;

    private Long ownerUserId;
    private Boolean isPublic;
    private Boolean editable;
    private String style;

    /** DRAFT / PUBLISHED */
    private String status;

    /** True when the cover was uploaded by hand instead of derived from the first song. */
    private Boolean coverIsCustom;

    /**
     * 歌曲列表
     */
    private List<SongVO> songs;

    /**
     * 喜欢状态
     * 0：默认
     * 1：喜欢
     */
    private Integer likeStatus;

    /**
     * 评论列表
     */
    private List<CommentVO> comments;

}
