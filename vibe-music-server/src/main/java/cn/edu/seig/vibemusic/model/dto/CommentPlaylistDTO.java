package cn.edu.seig.vibemusic.model.dto;

import lombok.Data;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.io.Serial;
import java.io.Serializable;

@Data
public class CommentPlaylistDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 歌单id
     */
    @NotNull
    @Positive
    private Long playlistId;

    /**
     * 评论内容
     */
    @NotBlank
    @Size(max = 255)
    private String content;

}
