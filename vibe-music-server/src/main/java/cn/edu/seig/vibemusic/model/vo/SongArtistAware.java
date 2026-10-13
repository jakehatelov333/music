package cn.edu.seig.vibemusic.model.vo;

import java.util.List;

/**
 * 「可以携带结构化歌手列表」的歌曲视图。
 *
 * <p>引入这个接口是为了让装配逻辑只有一份：{@code SongArtistService#attachArtists} 接受
 * 任意实现了本接口的列表，而不必为 {@link SongVO} / {@link SongAdminVO} / {@link SongDetailVO}
 * 各写一遍几乎相同的代码。
 *
 * <p><b>注意</b>：{@link #getArtists()} 是<b>追加</b>字段，{@code artistName} 字符串仍然保留，
 * 且两者必须由同一份权威数据（{@code tb_song_artist}）派生，不允许各自计算。
 */
public interface SongArtistAware {

    /** 歌曲 id，用于把歌手列表挂到正确的行上。 */
    Long getSongId();

    /** 结构化歌手列表，按 display_order 升序；无关系时为不可变空列表而不是 null。 */
    void setArtists(List<ArtistNameVO> artists);
}
