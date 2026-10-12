package cn.edu.seig.vibemusic.model.agent;

/** 歌手的结构化引用。 */
public record ArtistRef(
        Long artistId,
        String artistName,
        String area,
        String gender,
        String birth,
        String introduction
) {
}
