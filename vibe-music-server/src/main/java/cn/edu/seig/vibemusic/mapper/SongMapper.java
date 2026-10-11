package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.entity.Song;
import cn.edu.seig.vibemusic.model.vo.SongAdminVO;
import cn.edu.seig.vibemusic.model.vo.SongDetailVO;
import cn.edu.seig.vibemusic.model.vo.SongVO;
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
public interface SongMapper extends BaseMapper<Song> {

    // 获取歌曲列表
    @Select("""
                SELECT 
                    s.id AS songId, 
                    s.album_id AS albumId,
                    s.name AS songName, 
                    COALESCE(al.name, s.album) AS album,
                    s.disc_number AS discNumber,
                    s.track_number AS trackNumber,
                    s.duration, 
                    s.cover_url AS coverUrl, 
                    s.audio_url AS audioUrl, 
                    s.release_time AS releaseTime, 
                    a.name AS artistName
                FROM tb_song s
                LEFT JOIN tb_artist a ON s.artist_id = a.id
                LEFT JOIN tb_album al ON s.album_id = al.id
                WHERE 
                    (#{songName} IS NULL OR TRIM(#{songName}) = '' OR s.name LIKE CONCAT('%', TRIM(#{songName}), '%'))
                    AND (#{artistName} IS NULL OR TRIM(#{artistName}) = '' OR a.name LIKE CONCAT('%', TRIM(#{artistName}), '%'))
                    AND (#{album} IS NULL OR TRIM(#{album}) = '' OR COALESCE(al.name, s.album) LIKE CONCAT('%', TRIM(#{album}), '%'))
            """)
    IPage<SongVO> getSongsWithArtist(Page<SongVO> page,
                                     @Param("songName") String songName,
                                     @Param("artistName") String artistName,
                                     @Param("album") String album);

    // 获取歌曲列表
    @Select("""
                SELECT 
                    s.id AS songId, 
                    s.name AS songName, 
                    s.artist_id AS artistId, 
                    s.album_id AS albumId,
                    COALESCE(al.name, s.album) AS album,
                    s.disc_number AS discNumber,
                    s.track_number AS trackNumber,
                    s.lyric, 
                    s.lyric_source AS lyricSource,
                    s.lyric_status AS lyricStatus,
                    s.duration, 
                    s.style, 
                    s.cover_url AS coverUrl, 
                    s.audio_url AS audioUrl, 
                    s.release_time AS releaseTime, 
                    a.name AS artistName
                FROM tb_song s
                LEFT JOIN tb_artist a ON s.artist_id = a.id
                LEFT JOIN tb_album al ON s.album_id = al.id
                WHERE 
                    (#{artistId} IS NULL OR s.artist_id = #{artistId})
                    AND (#{songName} IS NULL OR TRIM(#{songName}) = '' OR s.name LIKE CONCAT('%', TRIM(#{songName}), '%'))
                    AND (#{album} IS NULL OR TRIM(#{album}) = '' OR COALESCE(al.name, s.album) LIKE CONCAT('%', TRIM(#{album}), '%'))
                    AND (COALESCE(#{onlyMissingLyric}, FALSE) = FALSE
                         OR ((s.lyric IS NULL OR TRIM(s.lyric) = '') AND COALESCE(s.lyric_status, 0) <> 4))
                ORDER BY s.release_time DESC
            """)
    IPage<SongAdminVO> getSongsWithArtistName(Page<SongAdminVO> page,
                                              @Param("artistId") Long artistId,
                                              @Param("songName") String songName,
                                              @Param("album") String album,
                                              @Param("onlyMissingLyric") Boolean onlyMissingLyric);

    /**
     * 获取随机歌曲列表
     * ORDER BY RAND() 作用:
     *                      表示按照随机顺序对查询结果进行排序。
     * 每次执行时，MySQL 会为每一行生成一个 0 到 1 之间的随机数，并根据这些数值对记录进行排序，从而打乱原本的顺序。
     * @return
     */
    @Select("""
                SELECT 
                    s.id AS songId, 
                    s.album_id AS albumId,
                    s.name AS songName, 
                    COALESCE(al.name, s.album) AS album,
                    s.disc_number AS discNumber,
                    s.track_number AS trackNumber,
                    s.duration, 
                    s.cover_url AS coverUrl, 
                    s.audio_url AS audioUrl, 
                    s.release_time AS releaseTime, 
                    a.name AS artistName
                FROM tb_song s
                LEFT JOIN tb_artist a ON s.artist_id = a.id
                LEFT JOIN tb_album al ON s.album_id = al.id
                ORDER BY RAND() LIMIT 20
            """)
    List<SongVO> getRandomSongsWithArtist();

    // 根据id获取歌曲详情
    SongDetailVO getSongDetailById(Long songId);

    // 根据用户收藏的歌曲id列表获取歌曲列表
    IPage<SongVO> getSongsByIds(Page<SongVO> page,
                                @Param("songIds") List<Long> songIds,
                                @Param("songName") String songName,
                                @Param("artistName") String artistName,
                                @Param("album") String album);

    // 根据用户收藏的歌曲id列表获取歌曲列表
    List<Long> getFavoriteSongStyles(@Param("favoriteSongIds") List<Long> favoriteSongIds);

    // 根据用户收藏的歌曲id列表获取歌曲列表
    List<SongVO> getRecommendedSongsByStyles(@Param("sortedStyleIds") List<Long> sortedStyleIds,
                                             @Param("favoriteSongIds") List<Long> favoriteSongIds,
                                             @Param("limit") int limit);

}
