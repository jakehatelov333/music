package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.entity.Playlist;
import cn.edu.seig.vibemusic.model.vo.AdminPlaylistVO;
import cn.edu.seig.vibemusic.model.vo.PlaylistDetailVO;
import cn.edu.seig.vibemusic.model.vo.PlaylistVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 * Mapper 接口
 * </p>
 *
 * @author sunpingli
 * @since 2025-01-09
 */
@Mapper
public interface PlaylistMapper extends BaseMapper<Playlist> {

    // 根据歌单id获取歌单详情
    PlaylistDetailVO getPlaylistDetailById(@Param("playlistId") Long playlistId, @Param("viewerId") Long viewerId);

    // 管理员查看平台歌单详情：不区分发布状态与可见性
    PlaylistDetailVO getAdminPlaylistDetailById(@Param("playlistId") Long playlistId);

    @Select("SELECT * FROM tb_playlist WHERE id = #{id} FOR UPDATE")
    @org.apache.ibatis.annotations.Results(id = "playlistEntity", value = {
            @org.apache.ibatis.annotations.Result(column = "id", property = "playlistId")
    })
    Playlist lockById(@Param("id") Long id);

    IPage<PlaylistVO> getOwnedPlaylists(Page<PlaylistVO> page, @Param("ownerId") Long ownerId,
                                     @Param("title") String title, @Param("style") String style);

    // 管理员歌单列表：包含平台歌单与用户歌单，按来源与发布状态筛选
    // orderBy 由 Service 层白名单解析后传入（不含 ORDER BY 关键字），禁止直接透传前端原值
    IPage<AdminPlaylistVO> getAdminPlaylists(Page<AdminPlaylistVO> page,
                                             @Param("title") String title,
                                             @Param("style") String style,
                                             @Param("status") String status,
                                             @Param("platform") Boolean platform,
                                             @Param("orderBy") String orderBy);

    /**
     * 公开歌单列表。viewerId 为空时按 id 倒序；否则自己创建的歌单排到最后，
     * 并返回 owned / favorite 标记供前端展示。
     */
    IPage<PlaylistVO> getPublicPlaylists(Page<PlaylistVO> page,
                                         @Param("viewerId") Long viewerId,
                                         @Param("title") String title,
                                         @Param("style") String style);

    /**
     * 歌单默认封面：取歌单中第一首带封面的歌曲（顺序与详情页 ORDER BY song_id 一致）。
     * 只读取歌曲封面，不会修改歌曲数据。
     */
    @Select("""
            SELECT s.cover_url
            FROM tb_playlist_binding pb
                     JOIN tb_song s ON s.id = pb.song_id
            WHERE pb.playlist_id = #{playlistId}
              AND s.cover_url IS NOT NULL
              AND s.cover_url <> ''
            ORDER BY pb.song_id
            LIMIT 1
            """)
    String getFirstBoundSongCover(@Param("playlistId") Long playlistId);

    // 获取用户收藏歌单的风格
    List<String> getFavoritePlaylistStyles(@Param("favoritePlaylistIds") List<Long> favoritePlaylistIds);

    // 根据风格推荐歌单（排除已收藏歌单）
    List<PlaylistVO> getRecommendedPlaylistsByStyles(@Param("styles") List<String> styles,
            @Param("favoritePlaylistIds") List<Long> favoritePlaylistIds, @Param("limit") int limit);

    // 随机推荐歌单：仅已发布的公开歌单
    @Select("""
            SELECT 
                p.id AS playlistId, 
                p.title AS title, 
                p.cover_url AS coverUrl
            FROM tb_playlist p
            WHERE p.is_public = 1
              AND (p.status IS NULL OR p.status = 'PUBLISHED')
            ORDER BY RAND() 
            LIMIT #{limit}
            """)
    List<PlaylistVO> getRandomPlaylists(@Param("limit") int limit);

    // 根据用户收藏的歌单id列表获取歌单列表
    IPage<PlaylistVO> getPlaylistsByIds(
            @Param("userId") Long userId,
            Page<PlaylistVO> page,
            @Param("playlistIds") List<Long> playlistIds,
            @Param("title") String title,
            @Param("style") String style);
}
