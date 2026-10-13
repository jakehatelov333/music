package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.agent.SongArtistRef;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

/**
 * 歌曲-演唱歌手关系（{@code tb_song_artist}）的只读查询。
 *
 * <p>刻意用注解 SQL 而不是 XML：测试基座只把 {@code mapper/PlaylistMapper.xml} 注册进 MyBatis，
 * 新增 XML 需要同步改 {@code AbstractAgentTest}（与 {@code UserMusicProfileMapper} 同一理由）。
 *
 * <p>调用方必须保证 {@code songIds} 非空 —— 空集合会生成非法的 {@code IN ()}。
 * 统一由 {@code SongArtistService} 做这层保护。
 */
@Mapper
public interface SongArtistMapper {

    /**
     * 按歌曲 id 批量取歌手关系，一次往返完成，避免逐首歌查询造成 N+1。
     *
     * <p>排序与展示顺序严格一致（{@code display_order} 优先、{@code artist_id} 兜底），
     * 因此调用方只需按 {@code songId} 分组即可，不需要二次排序。
     */
    @Select("""
            <script>
            SELECT sa.song_id AS songId,
                   sa.artist_id AS artistId,
                   a.name AS artistName,
                   sa.display_order AS displayOrder
            FROM tb_song_artist sa
            INNER JOIN tb_artist a ON a.id = sa.artist_id
            WHERE sa.song_id IN
            <foreach collection="songIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY sa.song_id, sa.display_order, sa.artist_id
            </script>
            """)
    List<SongArtistRef> findBySongIds(@Param("songIds") Collection<Long> songIds);

    /** 某位歌手参与演唱的歌曲 id（含合作歌曲），用于歌手改名后重建展示串等场景。 */
    @Select("""
            SELECT sa.song_id
            FROM tb_song_artist sa
            WHERE sa.artist_id = #{artistId}
            ORDER BY sa.song_id
            """)
    List<Long> findSongIdsByArtistId(@Param("artistId") Long artistId);

    // ------------------------------------------------------------------ 写操作
    // 全部由 SongArtistService 在单个事务内调用；调用方不得绕过该服务直接使用。

    /**
     * 同步主表的两个派生列：主艺人（display_order 最小者）与展示串。
     * 这两列与关系表必须由同一个事务写入，否则会出现「关系已改、展示串没改」的中间态。
     */
    @Update("""
            UPDATE tb_song
            SET artist_id = #{primaryArtistId}, artist_names = #{artistNames}
            WHERE id = #{songId}
            """)
    int updateSongArtistSummary(@Param("songId") Long songId,
                                @Param("primaryArtistId") Long primaryArtistId,
                                @Param("artistNames") String artistNames);

    /** 清空某首歌的全部关系（整组替换的第一步）。 */
    @Delete("DELETE FROM tb_song_artist WHERE song_id = #{songId}")
    int deleteBySongId(@Param("songId") Long songId);

    /**
     * 按传入顺序整组写入关系，{@code display_order} 取 {@code index + 1}（1 起连续）。
     *
     * <p>之所以「先删后插」而不是逐行 UPDATE：{@code display_order} 没有唯一约束是为了避免
     * 交换顺序时撞键，代价就是顺序只能整组重建 —— 一首歌的歌手数极少，代价可忽略。
     */
    @Insert("""
            <script>
            INSERT INTO tb_song_artist(song_id, artist_id, display_order) VALUES
            <foreach collection="artistIds" item="artistId" index="index" separator=",">
                (#{songId}, #{artistId}, #{index} + 1)
            </foreach>
            </script>
            """)
    int insertRelations(@Param("songId") Long songId, @Param("artistIds") List<Long> artistIds);

    /** 事务内自检：确认关系行数与预期一致，不一致就抛异常让整个事务回滚。 */
    @Select("SELECT COUNT(*) FROM tb_song_artist WHERE song_id = #{songId}")
    int countBySongId(@Param("songId") Long songId);
}
