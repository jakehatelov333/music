package cn.edu.seig.vibemusic.model.agent;

/**
 * 歌曲的结构化引用。字段与前端 {@code Song} / {@code PlaylistSong} 类型对齐，
 * 使同一份数据既能进模型上下文，也能直接渲染为可播放卡片。
 *
 * <p>{@code songId} 必须是数据库真实主键——下游的“创建歌单”写操作只接受这里返回的 ID。
 */
public record SongRef(
        Long songId,
        String songName,
        String artistName,
        String album,
        String style,
        String duration,
        String coverUrl,
        String audioUrl
) {
}
