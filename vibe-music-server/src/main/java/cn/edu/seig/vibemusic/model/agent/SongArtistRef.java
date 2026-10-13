package cn.edu.seig.vibemusic.model.agent;

import lombok.Data;

/**
 * {@code tb_song_artist} 的一行投影：某首歌的某位演唱歌手及其展示顺序。
 *
 * <p>刻意用普通 bean 而不是 record —— 与 {@link TasteCount} 同理：这张结果集由 MyBatis
 * 直接映射，bean 的 setter 映射路径最稳，不依赖编译期 {@code -parameters} 保留构造器参数名。
 *
 * <p>它承载的是<b>权威关系</b>，不是展示串；{@code artist_names} 由它派生。
 */
@Data
public class SongArtistRef {

    /** 歌曲 id */
    private Long songId;

    /** 歌手 id */
    private Long artistId;

    /** 歌手名（取自 tb_artist.name） */
    private String artistName;

    /** 展示顺序，从 1 开始 */
    private Integer displayOrder;
}
