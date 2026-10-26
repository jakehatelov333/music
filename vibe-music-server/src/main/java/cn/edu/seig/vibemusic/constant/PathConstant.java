package cn.edu.seig.vibemusic.constant;

public class PathConstant {

    public static final String PLAYLIST_DETAIL_PATH = "/playlist/getPlaylistDetail/**";
    /** 公开歌单列表：允许匿名访问，但会校验可选携带的登录态（用于排序与收藏标记） */
    public static final String PLAYLIST_LIST_PATH = "/playlist/getAllPlaylists";
    public static final String ARTIST_DETAIL_PATH = "/artist/getArtistDetail/**";
    public static final String SONG_LIST_PATH = "/song/getAllSongs";
    public static final String SONG_DETAIL_PATH = "/song/getSongDetail/**";
    public static final String ALBUM_LIST_PATH = "/album/list";
    public static final String ALBUM_DETAIL_PATH = "/album/**";
    public static final String AI_PATH="/ai/**";
    /** 精确路径与子路径都要覆盖；AntPathMatcher 不保证 {@code /search/**} 匹配 {@code /search}。 */
    public static final String SEARCH_PATH = "/search";
    public static final String SEARCH_SUBPATH = "/search/**";
}
