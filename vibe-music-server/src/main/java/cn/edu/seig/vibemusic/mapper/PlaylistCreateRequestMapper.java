package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.entity.PlaylistCreateRequest;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PlaylistCreateRequestMapper {
    // The unique user/key pair serializes concurrent retries in the database transaction.
    @Insert("""
            INSERT INTO tb_playlist_create_request (user_id, request_key, request_hash)
            VALUES (#{userId}, #{key}, #{hash})
            ON DUPLICATE KEY UPDATE request_key = request_key
            """)
    int reserve(@Param("userId") Long userId, @Param("key") String key, @Param("hash") String hash);

    @Select("""
            SELECT user_id, request_key, request_hash, playlist_id FROM tb_playlist_create_request
            WHERE user_id = #{userId} AND request_key = #{key} FOR UPDATE
            """)
    PlaylistCreateRequest lock(@Param("userId") Long userId, @Param("key") String key);

    @Update("""
            UPDATE tb_playlist_create_request SET playlist_id = #{playlistId}
            WHERE user_id = #{userId} AND request_key = #{key}
            """)
    int complete(@Param("userId") Long userId, @Param("key") String key, @Param("playlistId") Long playlistId);
}
