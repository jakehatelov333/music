package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.entity.UserFavorite;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
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
public interface UserFavoriteMapper extends BaseMapper<UserFavorite> {

    // Unique indexes make duplicate requests (including simultaneous requests) harmless.
    @Insert("INSERT INTO tb_user_favorite(user_id,type,song_id,create_time) " +
            "VALUES(#{userId},0,#{songId},CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE id=id")
    int insertSongIfAbsent(@Param("userId") Long userId, @Param("songId") Long songId);

    @Insert("INSERT INTO tb_user_favorite(user_id,type,playlist_id,create_time) " +
            "VALUES(#{userId},1,#{playlistId},CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE id=id")
    int insertPlaylistIfAbsent(@Param("userId") Long userId, @Param("playlistId") Long playlistId);

    // 查询用户收藏的所有歌曲ID
    @Select("SELECT song_id FROM tb_user_favorite WHERE user_id = #{userId} AND type = 0 ORDER BY create_time DESC")
    List<Long> getUserFavoriteSongIds(@Param("userId") Long userId);

    // 查询用户收藏的所有歌单ID
    @Select("SELECT playlist_id FROM tb_user_favorite WHERE user_id = #{userId} AND type = 1 ORDER BY create_time DESC")
    List<Long> getUserFavoritePlaylistIds(@Param("userId") Long userId);

    // 查询用户收藏的所有歌曲ID
    @Select("SELECT song_id FROM tb_user_favorite WHERE user_id = #{userId} AND type = 0")
    List<Long> getFavoriteSongIdsByUserId(@Param("userId") Long userId);

    // 查询用户收藏的所有歌单ID
    @Select("SELECT playlist_id FROM tb_user_favorite WHERE user_id = #{userId} AND type = 1")
    List<Long> getFavoritePlaylistIdsByUserId(@Param("userId") Long userId);

    // 根据 style 查询对应的 id
    List<Long> getFavoriteIdsByStyle(List<String> favoriteStyles);

}
