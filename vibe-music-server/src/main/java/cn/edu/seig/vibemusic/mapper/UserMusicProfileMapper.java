package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.agent.TasteCount;
import cn.edu.seig.vibemusic.model.entity.UserMusicProfile;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户音乐画像的持久化与聚合。
 *
 * <p><b>为什么全部用注解 SQL：</b>测试基座只把 {@code mapper/PlaylistMapper.xml} 注册进
 * MyBatis，新增 XML 需要同步改 {@code AbstractAgentTest}。注解 SQL 无需注册，
 * 且所有语句都刻意避开数据库方言函数（只用 UNION ALL / COUNT(DISTINCT) / LIMIT / 子查询），
 * MySQL 8 与 H2(MODE=MySQL) 都能跑。
 *
 * <p><b>聚合口径：</b>收藏歌曲与自建歌单歌曲用 {@code UNION ALL} 合并后再去重统计，
 * 同一首歌即使既被收藏又在自建歌单里也只算一次。
 */
@Mapper
public interface UserMusicProfileMapper {

    @Select("SELECT * FROM tb_user_music_profile WHERE user_id = #{userId}")
    UserMusicProfile find(@Param("userId") Long userId);

    /**
     * 事实计数（一次往返）。只返回 4 个计数列，TOP N 由另外两条语句单独取，
     * 避免把多行聚合硬塞进一次查询。
     */
    @Select("""
            SELECT
              (SELECT COUNT(*) FROM tb_user_favorite WHERE user_id = #{userId} AND type = 0) AS fav_song_count,
              (SELECT COUNT(*) FROM tb_user_favorite WHERE user_id = #{userId} AND type = 1) AS fav_playlist_count,
              (SELECT COUNT(*) FROM tb_playlist WHERE owner_user_id = #{userId}) AS own_playlist_count,
              (SELECT COUNT(DISTINCT song_id) FROM (
                    SELECT f.song_id FROM tb_user_favorite f
                     WHERE f.user_id = #{userId} AND f.type = 0 AND f.song_id IS NOT NULL
                    UNION ALL
                    SELECT b.song_id FROM tb_playlist_binding b
                      JOIN tb_playlist p ON p.id = b.playlist_id
                     WHERE p.owner_user_id = #{userId}
               ) t) AS own_playlist_song_count
            """)
    UserMusicProfile aggregateCounts(@Param("userId") Long userId);

    /**
     * 高频风格的<b>原始值</b>统计：来自「收藏歌曲 ∪ 自建歌单歌曲」。空风格不计入。
     *
     * <p>注意 {@code tb_song.style} 是<b>逗号分隔多值</b>（如 {@code 欧美流行,电子}），
     * 因此这里返回的 {@code name} 可能是混合串。拆分与合并计数由
     * {@code UserMusicProfileService#expandStyles} 负责 —— SQL 里做字符串拆分不可移植
     * （MySQL 与 H2 都没有通用写法）。
     */
    @Select("""
            SELECT s.style AS name, COUNT(DISTINCT t.song_id) AS count
            FROM (
                SELECT f.song_id AS song_id FROM tb_user_favorite f
                 WHERE f.user_id = #{userId} AND f.type = 0 AND f.song_id IS NOT NULL
                UNION ALL
                SELECT b.song_id AS song_id FROM tb_playlist_binding b
                  JOIN tb_playlist p ON p.id = b.playlist_id
                 WHERE p.owner_user_id = #{userId}
            ) t JOIN tb_song s ON s.id = t.song_id
            WHERE s.style IS NOT NULL AND s.style <> ''
            GROUP BY s.style
            ORDER BY count DESC, s.style ASC
            LIMIT #{limit}
            """)
    List<TasteCount> topStyles(@Param("userId") Long userId, @Param("limit") int limit);

    /**
     * 高频歌手：走 {@code tb_song_artist} 关联表，因此<b>合作歌曲对每位参与歌手各计一次</b>。
     *
     * <p>为什么不能再用 {@code a.id = s.artist_id}：那只会把歌曲算给「主艺人」，
     * 合作歌曲的其它演唱者在画像里会永远缺失（这正是改造前的口径错误）。
     *
     * <p>{@code COUNT(DISTINCT t.song_id)} 同时承担两件事：让一首歌对每位歌手各计一次，
     * 以及抵消 {@code t} 子查询里 UNION ALL 可能带来的重复行。
     */
    @Select("""
            SELECT a.id AS id, a.name AS name, COUNT(DISTINCT t.song_id) AS count
            FROM (
                SELECT f.song_id AS song_id FROM tb_user_favorite f
                 WHERE f.user_id = #{userId} AND f.type = 0 AND f.song_id IS NOT NULL
                UNION ALL
                SELECT b.song_id AS song_id FROM tb_playlist_binding b
                  JOIN tb_playlist p ON p.id = b.playlist_id
                 WHERE p.owner_user_id = #{userId}
            ) t JOIN tb_song s ON s.id = t.song_id
              JOIN tb_song_artist sa ON sa.song_id = s.id
              JOIN tb_artist a ON a.id = sa.artist_id
            WHERE a.status = 'ACTIVE'
            GROUP BY a.id, a.name
            ORDER BY count DESC, a.name ASC
            LIMIT #{limit}
            """)
    List<TasteCount> topArtists(@Param("userId") Long userId, @Param("limit") int limit);

    /**
     * Upsert 事实部分。
     *
     * <p>两个刻意的选择：
     * <ul>
     *     <li><b>不写 {@code VALUES(col)}</b> —— H2 的 MySQL 兼容模式对该函数支持不稳定，
     *         直接重复绑定参数两边都能跑；</li>
     *     <li><b>不覆盖 pref_summary / summary_source_hash</b> —— 那是异步概述生成的产物，
     *         由独立语句更新。刷新事实不应该顺手把已经写好的概述清掉。</li>
     * </ul>
     */
    @Insert("""
            INSERT INTO tb_user_music_profile
                (user_id, top_styles, top_artists, fav_song_count, fav_playlist_count,
                 own_playlist_count, own_playlist_song_count, version, updated_at)
            VALUES (#{p.userId}, #{p.topStyles}, #{p.topArtists}, #{p.favSongCount}, #{p.favPlaylistCount},
                    #{p.ownPlaylistCount}, #{p.ownPlaylistSongCount}, 1, #{now})
            ON DUPLICATE KEY UPDATE
                top_styles = #{p.topStyles}, top_artists = #{p.topArtists},
                fav_song_count = #{p.favSongCount}, fav_playlist_count = #{p.favPlaylistCount},
                own_playlist_count = #{p.ownPlaylistCount},
                own_playlist_song_count = #{p.ownPlaylistSongCount},
                version = version + 1, updated_at = #{now}
            """)
    int upsertFacts(@Param("p") UserMusicProfile profile, @Param("now") LocalDateTime now);

    /**
     * 仅当指纹与传入值不同（或从未生成过）时写入偏好概述。
     *
     * <p>指纹条件同时承担两个职责：
     * <ul>
     *     <li><b>省钱</b>——事实没变就不重复调模型；</li>
     *     <li><b>防并发覆盖</b>——两个异步任务拿着不同指纹同时回来时，
     *         先写者生效，后写者条件不命中直接跳过，不会把更新的概述覆盖回旧内容。</li>
     * </ul>
     */
    @Update("""
            UPDATE tb_user_music_profile
            SET pref_summary = #{summary}, summary_source_hash = #{hash}
            WHERE user_id = #{userId}
              AND (summary_source_hash IS NULL OR summary_source_hash <> #{hash})
            """)
    int updateSummaryIfStale(@Param("userId") Long userId, @Param("summary") String summary,
                             @Param("hash") String hash);
}
