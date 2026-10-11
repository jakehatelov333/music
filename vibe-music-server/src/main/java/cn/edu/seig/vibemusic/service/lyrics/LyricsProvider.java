package cn.edu.seig.vibemusic.service.lyrics;

import java.util.List;

/**
 * 歌词数据源抽象。
 * <p>
 * 换源只需新增一个实现类，业务层无需改动。实现类应自行处理超时、重试，
 * 并在失败时返回空列表而不是抛出异常（单个源失败不应阻断整条链路）。
 */
public interface LyricsProvider {

    /**
     * 查询条件。
     *
     * @param trackName       歌名
     * @param artistName      歌手
     * @param albumName       专辑，可为 null
     * @param durationSeconds 本地时长（秒），可为 null，用于版本消歧
     */
    record Query(String trackName, String artistName, String albumName, Double durationSeconds) {
    }

    /** 来源标识，写入 tb_song.lyric_source */
    String source();

    /** 是否启用（由配置控制） */
    boolean enabled();

    /**
     * 数据源优先级，数值越小越先尝试。
     * QQ 音乐能返回词曲作者，作为主源；LRCLIB 无制作信息，作为兜底。
     */
    default int order() {
        return 100;
    }

    /**
     * 按关键词搜索候选歌词。
     *
     * @return 候选列表；查询失败或无结果时返回空列表
     */
    List<LyricsCandidate> search(Query query);

}
