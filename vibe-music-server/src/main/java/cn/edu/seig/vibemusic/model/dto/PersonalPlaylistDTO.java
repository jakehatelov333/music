package cn.edu.seig.vibemusic.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PersonalPlaylistDTO {
    @NotBlank
    @Size(max = 255)
    private String title;
    @Size(max = 2000)
    private String introduction;
    @Size(max = 64)
    private String style;
    private Boolean isPublic = false;
}
