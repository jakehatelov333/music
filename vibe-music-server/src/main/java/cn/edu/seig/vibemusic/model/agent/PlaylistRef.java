package cn.edu.seig.vibemusic.model.agent;

/** 歌单的结构化引用。 */
public record PlaylistRef(
        Long playlistId,
        String title,
        String style,
        String introduction,
        String coverUrl
) {
}
