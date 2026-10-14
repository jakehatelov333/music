package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.entity.Album;
import cn.edu.seig.vibemusic.model.vo.AlbumDetailVO;
import cn.edu.seig.vibemusic.model.vo.AlbumVO;
import cn.edu.seig.vibemusic.model.vo.SongVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 专辑 Mapper。
 */
@Mapper
public interface AlbumMapper extends BaseMapper<Album> {

    @Select("""
            SELECT
                a.id AS albumId,
                a.artist_id AS artistId,
                a.name AS albumName,
                ar.name AS artistName,
                COALESCE(a.cover_url, MIN(s.cover_url)) AS coverUrl,
                COALESCE(a.release_date, MIN(s.release_time)) AS releaseDate,
                a.description AS description,
                a.status AS status,
                a.created_at AS createdAt,
                a.updated_at AS updatedAt,
                COUNT(s.id) AS songCount
            FROM tb_album a
            LEFT JOIN tb_artist ar ON a.artist_id = ar.id
            LEFT JOIN tb_song s ON s.album_id = a.id
            WHERE (#{albumName} IS NULL OR TRIM(#{albumName}) = ''
                   OR a.name LIKE CONCAT('%', TRIM(#{albumName}), '%'))
              AND (#{artistId} IS NULL OR a.artist_id = #{artistId})
              AND (#{keyword} IS NULL OR TRIM(#{keyword}) = ''
                   OR a.name LIKE CONCAT('%', TRIM(#{keyword}), '%')
                   OR ar.name LIKE CONCAT('%', TRIM(#{keyword}), '%'))
              AND (#{status} IS NULL OR TRIM(#{status}) = '' OR a.status = #{status})
              AND (COALESCE(#{excludeDisabledArtist}, FALSE) = FALSE OR ar.status = 'ACTIVE')
            GROUP BY a.id, a.artist_id, a.name, ar.name, a.cover_url, a.release_date, a.description,
                     a.status, a.created_at, a.updated_at
            ORDER BY ${orderBy}
            """)
    // ⚠️ ${orderBy} 为字符串替换：只允许传入 Service 层白名单映射后的固定子句，
    //    严禁把前端原始输入拼进来，否则存在 SQL 注入风险。
    IPage<AlbumVO> getAlbumList(Page<AlbumVO> page,
                                @Param("albumName") String albumName,
                                @Param("artistId") Long artistId,
                                @Param("keyword") String keyword,
                                @Param("status") String status,
                                @Param("excludeDisabledArtist") Boolean excludeDisabledArtist,
                                @Param("orderBy") String orderBy);

    @Select("""
            SELECT
                a.id AS albumId,
                a.artist_id AS artistId,
                a.name AS albumName,
                ar.name AS artistName,
                COALESCE(a.cover_url, MIN(s.cover_url)) AS coverUrl,
                COALESCE(a.release_date, MIN(s.release_time)) AS releaseDate,
                a.description AS description,
                a.status AS status,
                a.created_at AS createdAt,
                a.updated_at AS updatedAt,
                COUNT(s.id) AS songCount
            FROM tb_album a
            LEFT JOIN tb_artist ar ON a.artist_id = ar.id
            LEFT JOIN tb_song s ON s.album_id = a.id
            WHERE a.id = #{albumId}
              AND ar.status = 'ACTIVE'
            GROUP BY a.id, a.artist_id, a.name, ar.name, a.cover_url, a.release_date, a.description,
                     a.status, a.created_at, a.updated_at
            """)
    AlbumDetailVO getAlbumDetailById(@Param("albumId") Long albumId);

    /**
     * 专辑内的歌曲列表。
     *
     * <p><b>歌手名必须取歌曲自己的 {@code artist_names}，不能取专辑主艺人</b>：
     * 一首歌可能是合作演唱（例如《牵丝戏》是「银临 / Aki阿杰」），而专辑只有一个主艺人。
     * 早期实现这里 JOIN 了 {@code tb_artist}，导致整张专辑的歌曲都被显示成专辑主艺人的名字 ——
     * 这正是「搜索某位歌手时，歌曲只显示该歌手名字」的根因。
     */
    @Select("""
            SELECT
                s.id AS songId,
                s.name AS songName,
                s.album_id AS albumId,
                COALESCE(al.name, s.album) AS album,
                s.artist_names AS artistName,
                s.duration AS duration,
                s.cover_url AS coverUrl,
                s.audio_url AS audioUrl,
                s.release_time AS releaseTime,
                s.disc_number AS discNumber,
                s.track_number AS trackNumber,
                s.lyric_status AS lyricStatus
            FROM tb_song s
            INNER JOIN tb_album al ON s.album_id = al.id
            WHERE s.album_id = #{albumId}
              AND NOT EXISTS (
                    SELECT 1 FROM tb_song_artist sa
                    JOIN tb_artist a ON a.id = sa.artist_id
                    WHERE sa.song_id = s.id AND a.status <> 'ACTIVE')
            ORDER BY s.disc_number ASC, s.track_number ASC, s.id ASC
            """)
    List<SongVO> getSongsByAlbumId(@Param("albumId") Long albumId);

    @Select("""
            SELECT
                a.id AS albumId,
                a.artist_id AS artistId,
                a.name AS albumName,
                ar.name AS artistName,
                COALESCE(a.cover_url, MIN(s.cover_url)) AS coverUrl,
                COALESCE(a.release_date, MIN(s.release_time)) AS releaseDate,
                a.description AS description,
                a.status AS status,
                a.created_at AS createdAt,
                a.updated_at AS updatedAt,
                COUNT(s.id) AS songCount
            FROM tb_album a
            LEFT JOIN tb_artist ar ON a.artist_id = ar.id
            LEFT JOIN tb_song s ON s.album_id = a.id
            WHERE (a.name LIKE CONCAT('%', #{keyword}, '%')
               OR ar.name LIKE CONCAT('%', #{keyword}, '%'))
              AND ar.status = 'ACTIVE'
            GROUP BY a.id, a.artist_id, a.name, ar.name, a.cover_url, a.release_date, a.description,
                     a.status, a.created_at, a.updated_at
            ORDER BY
                CASE
                    WHEN a.name = #{keyword} THEN 0
                    WHEN a.name LIKE CONCAT(#{keyword}, '%') THEN 1
                    WHEN a.name LIKE CONCAT('%', #{keyword}, '%') THEN 2
                    WHEN ar.name = #{keyword} THEN 3
                    ELSE 4
                END,
                releaseDate IS NULL,
                releaseDate DESC,
                a.id DESC
            LIMIT #{limit}
            """)
    List<AlbumVO> searchAlbums(@Param("keyword") String keyword, @Param("limit") int limit);

    @Select("""
            SELECT
                a.id AS albumId,
                a.artist_id AS artistId,
                a.name AS albumName,
                ar.name AS artistName,
                COALESCE(a.cover_url, MIN(s.cover_url)) AS coverUrl,
                COALESCE(a.release_date, MIN(s.release_time)) AS releaseDate,
                a.description AS description,
                a.status AS status,
                a.created_at AS createdAt,
                a.updated_at AS updatedAt,
                COUNT(s.id) AS songCount
            FROM tb_album a
            LEFT JOIN tb_artist ar ON a.artist_id = ar.id
            LEFT JOIN tb_song s ON s.album_id = a.id
            WHERE a.artist_id = #{artistId}
              AND ar.status = 'ACTIVE'
            GROUP BY a.id, a.artist_id, a.name, ar.name, a.cover_url, a.release_date, a.description,
                     a.status, a.created_at, a.updated_at
            ORDER BY releaseDate IS NULL, releaseDate DESC, a.id DESC
            """)
    List<AlbumVO> getAlbumsByArtistId(@Param("artistId") Long artistId);

    @Select("""
            SELECT COALESCE(MAX(track_number), 0) + 1
            FROM tb_song
            WHERE album_id = #{albumId}
            """)
    Integer getNextTrackNumber(@Param("albumId") Long albumId);

    @Select("""
            SELECT COUNT(*)
            FROM tb_album a
            JOIN tb_artist ar ON ar.id = a.artist_id
            WHERE ar.status = 'ACTIVE'
            """)
    Long getVisibleAlbumCount();
}
