package cn.edu.seig.vibemusic.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

import java.io.Serial;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 
 * </p>
 *
 * @author sunpingli
 * @since 2025-01-09
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_playlist")
public class  Playlist implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 歌单 id
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long playlistId;

    /**
     * 歌单标题
     */
    @TableField("title")
    private String title;

    /**
     * 歌单封面
     */
    @TableField("cover_url")
    private String coverUrl;

    /**
     * 歌单简介
     */
    @TableField("introduction")
    private String introduction;

    /**
     * 歌单风格
     */
    @TableField("style")
    private String style;

    /** Null identifies a platform playlist. Ownership is never accepted from a request body. */
    @TableField("owner_user_id")
    private Long ownerUserId;

    @TableField("is_public")
    private Boolean isPublic;

    /** DRAFT / PUBLISHED, see {@link cn.edu.seig.vibemusic.enumeration.PlaylistStatusEnum}. */
    @TableField("status")
    private String status;

    /**
     * True once the owner uploaded a cover by hand.
     * A default cover derived from the first song is replaced automatically; a custom one never is.
     */
    @TableField("cover_is_custom")
    private Boolean coverIsCustom;

}
