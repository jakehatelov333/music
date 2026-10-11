package cn.edu.seig.vibemusic.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 全局音乐搜索结果。
 */
@Data
public class GlobalSearchVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private List<SongVO> songs;

    private List<ArtistVO> artists;

    private List<AlbumVO> albums;

    private List<PlaylistVO> playlists;
}
