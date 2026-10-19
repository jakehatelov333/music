package cn.edu.seig.vibemusic.service.lyrics;

/**
 * QQ 音乐搜索命中的一条曲目。
 *
 * <p>刻意保留 {@code albumMid}：歌词链路只关心 {@code songMid}，但封面链路完全依赖
 * {@code albumMid} 拼封面地址（{@code T002R{size}x{size}M000{albumMid}.jpg}）。
 * 历史上这个字段解析出来就被丢弃，导致「有搜索结果却拿不到封面」，所以在这里
 * 一次性把两侧需要的字段都保留下来。
 *
 * @param songMid         曲目 mid；QQ 音乐用 {@code "0"} 表示该曲目没有歌词（伴奏、语音备忘录等）
 * @param name            曲名，可能带版本后缀，例如 {@code 青花瓷 (Live)}
 * @param artist          歌手展示串，多位歌手用 {@code /} 连接，例如 {@code 银临/Aki阿杰}
 * @param albumName       专辑名，可能为空（翻唱、自制上传的曲目常常没有专辑）
 * @param albumMid        专辑 mid，可能为空；为空时无法拼出封面地址
 * @param intervalSeconds 曲目时长（秒），可能为空；用于版本消歧
 */
public record QqSongHit(String songMid,
                        String name,
                        String artist,
                        String albumName,
                        String albumMid,
                        Integer intervalSeconds) {

    /** 该曲目是否被 QQ 音乐标记为「无歌词」。 */
    public boolean lyricsUnavailable() {
        return songMid == null || songMid.isBlank() || "0".equals(songMid.trim());
    }

    /** 是否能拼出封面地址：必须有非空的 albumMid。 */
    public boolean hasAlbumCover() {
        return albumMid != null && !albumMid.isBlank();
    }

    /** 转成歌词候选（歌词正文由调用方另行拉取后回填）。 */
    public LyricsCandidate toCandidate(String lyric) {
        return new LyricsCandidate(name, artist, albumName, intervalSeconds, lyric, QqMusicProvider.SOURCE);
    }

}
