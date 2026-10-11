package cn.edu.seig.vibemusic.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 专辑实体。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_album")
public class Album implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 专辑 id
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long albumId;

    /**
     * 主艺人 id
     */
    @TableField("artist_id")
    private Long artistId;

    /**
     * 专辑名称
     */
    @TableField("name")
    private String albumName;

    /**
     * 专辑封面
     */
    @TableField("cover_url")
    private String coverUrl;

    /**
     * 发行日期
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @TableField("release_date")
    private LocalDate releaseDate;

    /**
     * 专辑简介
     */
    @TableField("description")
    private String description;

    /** DRAFT/PUBLISHED。草稿专辑允许没有歌曲。 */
    @TableField("status")
    private String status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
