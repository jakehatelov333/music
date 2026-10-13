package cn.edu.seig.vibemusic.model.agent;

import cn.edu.seig.vibemusic.model.vo.ArtistNameVO;

import java.util.List;

/**
 * 歌曲的结构化引用。字段与前端 {@code Song} / {@code PlaylistSong} 类型对齐，
 * 使同一份数据既能进模型上下文，也能直接渲染为可播放卡片。
 *
 * <p>{@code songId} 必须是数据库真实主键——下游的“创建歌单”写操作只接受这里返回的 ID。
 *
 * <p>{@code artistName} 与 {@code artists} 由同一份权威数据（{@code tb_song_artist}）派生：
 * 前者是多歌手展示串（兼容既有前端），后者带真实歌手 ID，供前端跳转歌手详情页。
 * 两者永远一致，不会出现「展示串有 Aki阿杰、artists 里没有」这种分裂。
 */
public record SongRef(
        Long songId,
        String songName,
        String artistName,
        String album,
        String style,
        String duration,
        String coverUrl,
        String audioUrl,
        List<ArtistNameVO> artists
) {
}
