package cn.edu.seig.vibemusic.model.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.List;

@Data
public class PlaylistSongsDTO {
    @NotEmpty
    @Size(max = 100)
    private List<@NotNull @Positive Long> songIds;
}
