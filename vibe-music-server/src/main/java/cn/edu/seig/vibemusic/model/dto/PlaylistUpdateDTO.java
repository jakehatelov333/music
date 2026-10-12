package cn.edu.seig.vibemusic.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 管理员更新平台歌单基本信息。发布状态不在此接口变更，请使用发布/取消发布接口；
 * 平台歌单可见性固定为公开，不提供修改。
 */
@Data
public class PlaylistUpdateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 歌单 id
     */
    @NotNull
    @Positive
    private Long playlistId;

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

}
