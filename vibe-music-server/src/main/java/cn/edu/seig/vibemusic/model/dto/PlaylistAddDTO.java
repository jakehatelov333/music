package cn.edu.seig.vibemusic.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 管理员新增平台歌单。
 * <p>
 * 创建时始终落为草稿且可见性固定为公开（平台歌单不使用私人可见性，用草稿表达"尚未上线"）。
 * 支持同时带入初始歌曲，避免建出无法继续维护的空记录。
 */
@Data
public class PlaylistAddDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 歌单标题
     */
    @NotBlank
    @Size(max = 255)
    private String title;

    /**
     * 歌单简介
     */
    @Size(max = 2000)
    private String introduction;

    /**
     * 歌单风格
     */
    @Size(max = 64)
    private String style;

    /** 初始歌曲，最多 100 首，重复项会被忽略 */
    @Size(max = 100)
    private List<@NotNull @Positive Long> songIds;

}
