package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.entity.PlaylistBinding;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author sunpingli
 * @since 2025-01-09
 */
@Mapper
public interface PlaylistBindingMapper extends BaseMapper<PlaylistBinding> {

    // Unlike INSERT IGNORE, this does not hide missing-song FK or other data errors.
    @Insert("""
            INSERT INTO tb_playlist_binding (playlist_id, song_id) VALUES (#{playlistId}, #{songId})
            ON DUPLICATE KEY UPDATE song_id = song_id
            """)
    int addSongIfAbsent(@Param("playlistId") Long playlistId, @Param("songId") Long songId);

    @Delete("DELETE FROM tb_playlist_binding WHERE playlist_id = #{playlistId} AND song_id = #{songId}")
    int removeSong(@Param("playlistId") Long playlistId, @Param("songId") Long songId);

}
