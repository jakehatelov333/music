package cn.edu.seig.vibemusic.service.lyrics;

/**
 * 歌词候选，由各个 {@link LyricsProvider} 返回统一结构。
 *
 * @param trackName       数据源返回的歌名
 * @param artistName      数据源返回的歌手
 * @param albumName       数据源返回的专辑
 * @param durationSeconds 数据源返回的时长（秒），可能为 null
 * @param lyric           歌词原文（通常为 LRC，带时间轴）
 * @param source          来源标识：QQ_MUSIC / LRCLIB
 */
public record LyricsCandidate(
        String trackName,
        String artistName,
        String albumName,
        Integer durationSeconds,
        String lyric,
        String source
) {
}
