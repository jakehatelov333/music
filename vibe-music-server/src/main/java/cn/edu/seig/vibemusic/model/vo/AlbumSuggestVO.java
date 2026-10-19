package cn.edu.seig.vibemusic.model.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * 专辑联想建议项 —— 供「歌曲上传」页的专辑输入框做自动补全。
 *
 * <p>与 {@link AlbumVO} 的区别：只保留联想下拉需要的字段，并额外给出
 * {@code artistStatus}。管理端需要知道某条候选的歌手是否已停用 ——
 * 停用的歌手在用户端不可见，但它名下的专辑仍然真实存在，管理员上传时
 * 应该能看到（否则会出现「明明有这张专辑，却搜不到」的困惑）。
 */
@Data
public class AlbumSuggestVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long albumId;

    private String albumName;

    private Long artistId;

    private String artistName;

    /**
     * 歌手状态：ACTIVE / DISABLED。
     *
     * <p>管理端据此在下拉项上标注「已停用」，而不是把它藏起来。
     */
    private String artistStatus;

    private String coverUrl;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate releaseDate;

    private Long songCount;

}
