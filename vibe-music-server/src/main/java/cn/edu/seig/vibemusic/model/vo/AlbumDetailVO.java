package cn.edu.seig.vibemusic.model.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.util.List;

/**
 * 专辑详情，一次返回专辑信息与按曲序排列的歌曲。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AlbumDetailVO extends AlbumVO {

    @Serial
    private static final long serialVersionUID = 1L;

    private List<SongVO> songs;
}
