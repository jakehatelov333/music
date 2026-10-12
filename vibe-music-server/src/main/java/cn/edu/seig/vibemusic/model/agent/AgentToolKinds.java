package cn.edu.seig.vibemusic.model.agent;

/**
 * 工具结果的业务类型标识。前端按此字段决定渲染成什么组件。
 */
public final class AgentToolKinds {
    private AgentToolKinds() {}

    /** 纯文本，无需结构化渲染。 */
    public static final String TEXT = "TEXT";
    /** 歌曲列表，可渲染为可播放卡片。 */
    public static final String SONG_LIST = "SONG_LIST";
    /** 歌手列表。 */
    public static final String ARTIST_LIST = "ARTIST_LIST";
    /** 歌手详情。 */
    public static final String ARTIST_DETAIL = "ARTIST_DETAIL";
    /** 歌单列表。 */
    public static final String PLAYLIST_LIST = "PLAYLIST_LIST";
    /** 待确认的歌单预览（必须由用户在界面上确认）。 */
    public static final String PLAYLIST_PREVIEW = "PLAYLIST_PREVIEW";
    /** 已真实创建的歌单。 */
    public static final String PLAYLIST_CREATED = "PLAYLIST_CREATED";
}
