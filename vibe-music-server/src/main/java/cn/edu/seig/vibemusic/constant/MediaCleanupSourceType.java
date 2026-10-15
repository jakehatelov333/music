package cn.edu.seig.vibemusic.constant;

/**
 * {@code tb_media_cleanup_task.source_type} 的取值域 —— 媒体清理任务的来源业务。
 *
 * <p>与 {@code source_ref} 一起构成"同一业务操作"的标识：{@code (source_type, source_ref, key_digest)}
 * 上有唯一约束，所以同一次业务操作重复执行不会重复入队。
 *
 * <p>新增来源时必须同步 {@code sql/phaseC_media_cleanup.sql} 里该列的注释，避免文档与代码漂移。
 */
public final class MediaCleanupSourceType {

    /** 删除歌曲：封面 + 音频。{@code source_ref} = songId。 */
    public static final String SONG_DELETE = "SONG_DELETE";

    /** 删除专辑：专辑封面；{@code deleteSongs=true} 时还含各曲目的封面与音频。{@code source_ref} = albumId。 */
    public static final String ALBUM_DELETE = "ALBUM_DELETE";

    /** 替换歌曲封面：被换下的旧封面。{@code source_ref} = songId。 */
    public static final String SONG_COVER_REPLACE = "SONG_COVER_REPLACE";

    /** 替换歌曲音频：被换下的旧音频。{@code source_ref} = songId。 */
    public static final String SONG_AUDIO_REPLACE = "SONG_AUDIO_REPLACE";

    /** 替换专辑封面：被换下的旧封面。{@code source_ref} = albumId。 */
    public static final String ALBUM_COVER_REPLACE = "ALBUM_COVER_REPLACE";

    /** 个人歌单封面变更/清除：被换下或清掉的自定义封面。{@code source_ref} = playlistId。 */
    public static final String PLAYLIST_COVER = "PLAYLIST_COVER";

    /** 上传会话中止：暂存对象。本阶段未接入（留给 E 阶段），占位以固定取值域。 */
    public static final String UPLOAD_ABORT = "UPLOAD_ABORT";

    private MediaCleanupSourceType() {
    }
}
