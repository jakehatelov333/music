package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.vo.ArtistVO;
import cn.edu.seig.vibemusic.model.vo.AlbumVO;
import cn.edu.seig.vibemusic.model.vo.PlaylistVO;
import cn.edu.seig.vibemusic.model.vo.SongVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 全局音乐搜索 Mapper。
 */
@Mapper
public interface SearchMapper {

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
            WHERE s.name LIKE CONCAT('%', #{keyword}, '%')
               OR a.name LIKE CONCAT('%', #{keyword}, '%')
               OR COALESCE(al.name, s.album) LIKE CONCAT('%', #{keyword}, '%')
               OR s.style LIKE CONCAT('%', #{keyword}, '%')
            ORDER BY
                CASE
                    WHEN s.name = #{keyword} THEN 0
                    WHEN s.name LIKE CONCAT(#{keyword}, '%') THEN 1
                    WHEN s.name LIKE CONCAT('%', #{keyword}, '%') THEN 2
                    WHEN a.name = #{keyword} THEN 3
                    WHEN a.name LIKE CONCAT(#{keyword}, '%') THEN 4
                    WHEN a.name LIKE CONCAT('%', #{keyword}, '%') THEN 5
                    ELSE 6
                END,
                s.release_time DESC,
                s.id DESC
            LIMIT #{limit}
            """)
    List<SongVO> searchSongs(@Param("keyword") String keyword, @Param("limit") int limit);

    @Select("""
            SELECT
                a.id AS artistId,
                a.name AS artistName,
                a.avatar
            FROM tb_artist a
            WHERE a.name LIKE CONCAT('%', #{keyword}, '%')
               OR a.area LIKE CONCAT('%', #{keyword}, '%')
            ORDER BY
                CASE
                    WHEN a.name = #{keyword} THEN 0
                    WHEN a.name LIKE CONCAT(#{keyword}, '%') THEN 1
                    WHEN a.name LIKE CONCAT('%', #{keyword}, '%') THEN 2
                    ELSE 3
                END,
                a.id DESC
            LIMIT #{limit}
            """)
    List<ArtistVO> searchArtists(@Param("keyword") String keyword, @Param("limit") int limit);

    @Select("""
            SELECT
                al.id AS albumId,
                al.artist_id AS artistId,
                al.name AS albumName,
                ar.name AS artistName,
                COALESCE(al.cover_url, MIN(s.cover_url)) AS coverUrl,
                COALESCE(al.release_date, MIN(s.release_time)) AS releaseDate,
                al.description AS description,
                COUNT(s.id) AS songCount
            FROM tb_album al
            LEFT JOIN tb_artist ar ON al.artist_id = ar.id
            LEFT JOIN tb_song s ON s.album_id = al.id
            WHERE al.name LIKE CONCAT('%', #{keyword}, '%')
               OR ar.name LIKE CONCAT('%', #{keyword}, '%')
            GROUP BY al.id, al.artist_id, al.name, ar.name, al.cover_url, al.release_date, al.description
            ORDER BY
                CASE
                    WHEN al.name = #{keyword} THEN 0
                    WHEN al.name LIKE CONCAT(#{keyword}, '%') THEN 1
                    WHEN al.name LIKE CONCAT('%', #{keyword}, '%') THEN 2
                    WHEN ar.name = #{keyword} THEN 3
                    ELSE 4
                END,
                releaseDate IS NULL,
                releaseDate DESC,
                al.id DESC
            LIMIT #{limit}
            """)
    List<AlbumVO> searchAlbums(@Param("keyword") String keyword, @Param("limit") int limit);

    @Select("""
            SELECT
                p.id AS playlistId,
                p.title,
                p.cover_url AS coverUrl
            FROM tb_playlist p
            WHERE p.title LIKE CONCAT('%', #{keyword}, '%')
               OR p.style LIKE CONCAT('%', #{keyword}, '%')
               OR p.introduction LIKE CONCAT('%', #{keyword}, '%')
            ORDER BY
                CASE
                    WHEN p.title = #{keyword} THEN 0
                    WHEN p.title LIKE CONCAT(#{keyword}, '%') THEN 1
                    WHEN p.title LIKE CONCAT('%', #{keyword}, '%') THEN 2
                    ELSE 3
                END,
                p.id DESC
            LIMIT #{limit}
            """)
    List<PlaylistVO> searchPlaylists(@Param("keyword") String keyword, @Param("limit") int limit);
}
