package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.agent.TasteCount;
import cn.edu.seig.vibemusic.model.entity.PlayHistory;
import cn.edu.seig.vibemusic.model.vo.PlayHistoryVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 播放历史的持久化与查询。
 *
 * <p><b>为什么全部用注解 SQL：</b>测试基座只把 {@code mapper/PlaylistMapper.xml} 注册进 MyBatis，
 * 新增 XML 需要同步改 {@code AbstractAgentTest}；注解 SQL 无需注册，
 * 且所有语句都刻意避开数据库方言函数，MySQL 8 与 H2(MODE=MySQL) 都能跑
 * （与 {@code UserMusicProfileMapper} 同一理由）。
 *
 * <p><b>每一条写/删语句都带 {@code user_id} 条件</b>，即使上层已经校验过归属。
 * 这样「拿别人的 historyId 来删」在 SQL 层面就不可能命中，而不是依赖调用方记得过滤。
 */
@Mapper
public interface PlayHistoryMapper extends BaseMapper<PlayHistory> {

    /**
     * 首次播放：插入一行历史。
     *
     * <p><b>刻意是裸 INSERT，不用 {@code ON DUPLICATE KEY UPDATE id = id}：</b>
     * MySQL Connector/J 默认使用 found-rows 语义（{@code useAffectedRows=false}），
     * 「命中了行但没有实际改动」也会返回 1。用它判断「是不是首次插入」在生产库上
     * 恒为真，会导致计数永远不累加（H2 返回 0，测试完全测不出来）。
     * 因此改为依赖唯一键抛 {@code DuplicateKeyException} 来识别并发冲突 ——
     * 那是标准异常，两种数据库语义一致。
     *
     * @return 恒为 1（插入成功）；冲突时抛 DuplicateKeyException
     */
    @Insert("""
            INSERT INTO tb_play_history
                (user_id, song_id, last_played_at, play_count, last_position_seconds, last_play_id)
            VALUES (#{userId}, #{songId}, #{playedAt}, 1, #{positionSeconds}, #{playId})
            """)
    int insertNew(@Param("userId") Long userId,
                  @Param("songId") Long songId,
                  @Param("playedAt") LocalDateTime playedAt,
                  @Param("positionSeconds") Integer positionSeconds,
                  @Param("playId") String playId);

    /**
     * 判断该用户是否已有这首歌的历史行。
     *
     * <p>用来区分「累加失败是因为行不存在」与「累加失败是因为幂等键拦下」。
     * 不用 affected-rows 推断，理由见 {@link #insertNew}。
     */
    @Select("SELECT COUNT(*) FROM tb_play_history WHERE user_id = #{userId} AND song_id = #{songId}")
    int exists(@Param("userId") Long userId, @Param("songId") Long songId);

    /**
     * 重复播放：累加次数并刷新最近播放时间。
     *
     * <p><b>为什么计数用 {@code play_count + 1} 而不是先读后写：</b>并发下「读 1 → 写 2」
     * 会丢失计数，数据库侧自增才是原子的。
     *
     * <p><b>幂等条件 {@code last_play_id <> playId}：</b>{@code playId} 是客户端为
     * 「一次播放尝试」生成的幂等键。网络重试、事件重复触发会带同一个 playId 再来一次，
     * 此时条件不命中、返回 0，计数不变 —— 这正是我们要的。
     * {@code playId} 为 null 时条件恒真（客户端明确不需要幂等）。
     *
     * <p>刻意不加 {@code LIMIT}：H2 与 MySQL 对 {@code UPDATE ... LIMIT} 的支持不一致，
     * 而 {@code (user_id, song_id)} 本身唯一，最多命中一行。
     *
     * @return 1 表示累加成功；0 表示被幂等键拦下（重复请求）
     */
    @Update("""
            UPDATE tb_play_history
            SET play_count = play_count + 1,
                last_played_at = #{playedAt},
                last_position_seconds = #{positionSeconds},
                last_play_id = #{playId}
            WHERE user_id = #{userId}
              AND song_id = #{songId}
              AND (#{playId} IS NULL OR last_play_id IS NULL OR last_play_id <> #{playId})
            """)
    int incrementPlay(@Param("userId") Long userId,
                      @Param("songId") Long songId,
                      @Param("playedAt") LocalDateTime playedAt,
                      @Param("positionSeconds") Integer positionSeconds,
                      @Param("playId") String playId);

    /**
     * 分页查询当前用户的播放历史，按最近播放时间倒序。
     *
     * <p>{@code INNER JOIN tb_song}：歌曲被删除后即便残留了历史行也不会返回给前端
     * （正常情况下 FK 已经级联删掉了）。JOIN 是一对一（song_id 是主键），
     * 因此分页 COUNT 不会被放大。
     *
     * <p>歌手展示串直接读主表冗余列 {@code artist_names}（多歌手已按 display_order 拼好），
     * 因此这里不 JOIN {@code tb_artist}，结构化歌手列表由 {@code SongArtistService} 二次装配。
     *
     * <p>{@code songName} 为空表示不筛选；LIKE 条件写在 JOIN 之后，
     * 保证「搜歌名」不会把用户没听过的歌带进来。
     */
    @Select("""
            <script>
            SELECT
                h.id             AS historyId,
                h.song_id        AS songId,
                h.last_played_at AS lastPlayedAt,
                h.play_count     AS playCount,
                s.name           AS songName,
                s.album_id       AS albumId,
                COALESCE(al.name, s.album) AS album,
                s.duration       AS duration,
                s.cover_url      AS coverUrl,
                s.audio_url      AS audioUrl,
                s.release_time   AS releaseTime,
                s.artist_names   AS artistName
            FROM tb_play_history h
            INNER JOIN tb_song s ON s.id = h.song_id
            LEFT JOIN tb_album al ON s.album_id = al.id
            WHERE h.user_id = #{userId}
            <if test="songName != null and songName != ''">
                AND s.name LIKE CONCAT('%', #{songName}, '%')
            </if>
            ORDER BY h.last_played_at DESC, h.id DESC
            </script>
            """)
    IPage<PlayHistoryVO> pageByUser(Page<PlayHistoryVO> page,
                                    @Param("userId") Long userId,
                                    @Param("songName") String songName);

    /**
     * 最近播放的歌曲（首页/侧栏的「刚刚听过」），与分页查询同一套口径与排序，固定取前 N 条。
     */
    @Select("""
            SELECT
                h.id             AS historyId,
                h.song_id        AS songId,
                h.last_played_at AS lastPlayedAt,
                h.play_count     AS playCount,
                s.name           AS songName,
                s.album_id       AS albumId,
                COALESCE(al.name, s.album) AS album,
                s.duration       AS duration,
                s.cover_url      AS coverUrl,
                s.audio_url      AS audioUrl,
                s.release_time   AS releaseTime,
                s.artist_names   AS artistName
            FROM tb_play_history h
            INNER JOIN tb_song s ON s.id = h.song_id
            LEFT JOIN tb_album al ON s.album_id = al.id
            WHERE h.user_id = #{userId}
            ORDER BY h.last_played_at DESC, h.id DESC
            LIMIT #{limit}
            """)
    List<PlayHistoryVO> recentByUser(@Param("userId") Long userId, @Param("limit") int limit);

    /**
     * 删除当前用户的一条历史。返回 0 表示这条记录不存在<b>或不属于该用户</b> ——
     * 两种情况在接口层统一返回 404，不泄漏资源存在性。
     */
    @Delete("DELETE FROM tb_play_history WHERE user_id = #{userId} AND id = #{historyId}")
    int deleteOwned(@Param("userId") Long userId, @Param("historyId") Long historyId);

    /** 清空当前用户的历史；只影响该用户。 */
    @Delete("DELETE FROM tb_play_history WHERE user_id = #{userId}")
    int clearByUser(@Param("userId") Long userId);

    /** 当前用户的历史条数（用于接口返回「清空了几条」，不作为独立统计接口对外暴露）。 */
    @Select("SELECT COUNT(*) FROM tb_play_history WHERE user_id = #{userId}")
    long countByUser(@Param("userId") Long userId);

    // ------------------------------------------------------------ 画像聚合（隐式信号）

    /**
     * 有过有效播放的不同歌曲数。与收藏计数<b>分开</b>统计，两者不合并。
     */
    @Select("SELECT COUNT(DISTINCT song_id) FROM tb_play_history WHERE user_id = #{userId}")
    int countDistinctPlayedSongs(@Param("userId") Long userId);

    /**
     * 反复播放的歌手 TOP N（隐式信号）。
     *
     * <p><b>为什么按「播放过的不同歌曲数」而不是「总播放次数」排序：</b>
     * 单曲循环一首歌会把总次数推到很高，但那只是「这首歌在循环」，
     * 不代表用户偏爱这位歌手。按不同歌曲数排序，要求歌手有<b>多首</b>歌被听过，
     * 更接近「偏好」而不是「一次播放」。
     *
     * <p>走 {@code tb_song_artist} 关联表，合作歌曲对每位参与歌手各计一次；
     * {@code COUNT(DISTINCT h.song_id)} 同时抵消关联行可能带来的重复。
     */
    @Select("""
            SELECT a.id AS id, a.name AS name, COUNT(DISTINCT h.song_id) AS count
            FROM tb_play_history h
            JOIN tb_song_artist sa ON sa.song_id = h.song_id
            JOIN tb_artist a ON a.id = sa.artist_id
            WHERE h.user_id = #{userId} AND a.status = 'ACTIVE'
            GROUP BY a.id, a.name
            ORDER BY count DESC, a.name ASC
            LIMIT #{limit}
            """)
    List<TasteCount> topPlayedArtists(@Param("userId") Long userId, @Param("limit") int limit);
}
