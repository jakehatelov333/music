package cn.edu.seig.vibemusic.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class PersonalPlaylistCreateDTO extends PersonalPlaylistDTO {
    @Size(max = 100)
    private List<@NotNull @Positive Long> songIds = List.of();
}
