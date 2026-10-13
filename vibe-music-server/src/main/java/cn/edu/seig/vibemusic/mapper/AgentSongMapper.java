package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.vo.AgentSongVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Agent 侧只读歌曲查询。
 *
 * <p>刻意与 {@link SongMapper} 分开：{@code SongMapper} 被大量传统 REST 接口复用，
 * 在其上新增方法会扩大影响面。这里只放 Agent 需要、且传统接口不关心的投影与筛选组合。
 * 所有查询都是只读的，且只返回数据库中真实存在的行。
 */
@Mapper
public interface AgentSongMapper {

    /** 分页插件使用同一组筛选条件计算总数；稳定 ID 排序避免翻页重复。 */
    @Select("""
            SELECT s.id AS songId, s.name AS songName, a.name AS artistName,
                   COALESCE(al.name, s.album) AS album, s.style AS style,
                   s.duration AS duration, s.cover_url AS coverUrl, s.audio_url AS audioUrl
            FROM tb_song s
            LEFT JOIN tb_artist a ON s.artist_id = a.id
            LEFT JOIN tb_album al ON s.album_id = al.id
            WHERE (#{artistName} IS NULL OR a.name LIKE CONCAT('%', #{artistName}, '%'))
              AND (#{style} IS NULL OR s.style LIKE CONCAT('%', #{style}, '%'))
              AND (#{keyword} IS NULL OR s.name LIKE CONCAT('%', #{keyword}, '%')
                   OR a.name LIKE CONCAT('%', #{keyword}, '%')
                   OR COALESCE(al.name, s.album) LIKE CONCAT('%', #{keyword}, '%')
                   OR s.style LIKE CONCAT('%', #{keyword}, '%'))
            ORDER BY s.id
            """)
    com.baomidou.mybatisplus.core.metadata.IPage<AgentSongVO> searchPage(
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<AgentSongVO> page,
            @Param("keyword") String keyword, @Param("artistName") String artistName,
            @Param("style") String style);

    /**
     * 组合筛选：歌名 / 歌手 / 专辑 / 风格，全部为可选条件（null 或空串表示不限制）。
     * 条件之间是 AND，用于“歌手 + 风格”这类精确组合检索。
     */
    @Select("""
            SELECT s.id AS songId,
                   s.name AS songName,
                   a.name AS artistName,
                   COALESCE(al.name, s.album) AS album,
                   s.style AS style,
                   s.duration AS duration,
                   s.cover_url AS coverUrl,
                   s.audio_url AS audioUrl
            FROM tb_song s
                     LEFT JOIN tb_artist a ON s.artist_id = a.id
                     LEFT JOIN tb_album al ON s.album_id = al.id
            WHERE (#{songName} IS NULL OR TRIM(#{songName}) = '' OR s.name LIKE CONCAT('%', TRIM(#{songName}), '%'))
              AND (#{artistName} IS NULL OR TRIM(#{artistName}) = '' OR a.name LIKE CONCAT('%', TRIM(#{artistName}), '%'))
              AND (#{album} IS NULL OR TRIM(#{album}) = '' OR COALESCE(al.name, s.album) LIKE CONCAT('%', TRIM(#{album}), '%'))
              AND (#{style} IS NULL OR TRIM(#{style}) = '' OR s.style LIKE CONCAT('%', TRIM(#{style}), '%'))
            ORDER BY s.id
            LIMIT #{limit}
            """)
    List<AgentSongVO> findSongs(@Param("songName") String songName,
                               @Param("artistName") String artistName,
                               @Param("album") String album,
                               @Param("style") String style,
                               @Param("limit") int limit);

    /**
     * 单一关键词的宽泛检索：歌名 / 歌手 / 专辑 / 风格之间是 <b>OR</b>。
     * 用户说“搜一下夜色”时，命中歌名或命中歌手都应该算命中，用 AND 会一个都搜不到。
     */
    @Select("""
            SELECT s.id AS songId,
                   s.name AS songName,
                   a.name AS artistName,
                   COALESCE(al.name, s.album) AS album,
                   s.style AS style,
                   s.duration AS duration,
                   s.cover_url AS coverUrl,
                   s.audio_url AS audioUrl
            FROM tb_song s
                     LEFT JOIN tb_artist a ON s.artist_id = a.id
                     LEFT JOIN tb_album al ON s.album_id = al.id
            WHERE s.name LIKE CONCAT('%', TRIM(#{keyword}), '%')
               OR a.name LIKE CONCAT('%', TRIM(#{keyword}), '%')
               OR COALESCE(al.name, s.album) LIKE CONCAT('%', TRIM(#{keyword}), '%')
               OR s.style LIKE CONCAT('%', TRIM(#{keyword}), '%')
            ORDER BY s.id
            LIMIT #{limit}
            """)
    List<AgentSongVO> findSongsByKeyword(@Param("keyword") String keyword, @Param("limit") int limit);

    /**
     * 歌单候选检索：与 {@link #findSongsByKeyword} 同样把关键词当作 <b>OR</b> 宽泛匹配，
     * 额外支持「歌手 + 风格」的精确收窄，并在 <b>SQL 里</b>排除 {@code excludeIds}。
     *
     * <p><b>为什么排除必须写进 SQL 而不是 Java 过滤：</b>如果先 {@code LIMIT 20} 取出前 20 首，
     * 再在内存里把已用过的歌剔掉，当曲库里其实还有很多未使用歌曲时，结果会被误判成「没有新歌」。
     * 这里 {@code NOT IN} 与 {@code LIMIT} 在同一条语句里，数据库先排除再截取，
     * 因此总能继续拿到后续歌曲。
     *
     * <p>{@code excludeIds} 为空时不加任何排除条件，行为与普通检索一致。
     */
    @Select("""
            <script>
            SELECT s.id AS songId,
                   s.name AS songName,
                   a.name AS artistName,
                   COALESCE(al.name, s.album) AS album,
                   s.style AS style,
                   s.duration AS duration,
                   s.cover_url AS coverUrl,
                   s.audio_url AS audioUrl
            FROM tb_song s
                     LEFT JOIN tb_artist a ON s.artist_id = a.id
                     LEFT JOIN tb_album al ON s.album_id = al.id
            WHERE (#{artistName} IS NULL OR TRIM(#{artistName}) = '' OR a.name LIKE CONCAT('%', TRIM(#{artistName}), '%'))
              AND (#{style} IS NULL OR TRIM(#{style}) = '' OR s.style LIKE CONCAT('%', TRIM(#{style}), '%'))
              AND (#{keyword} IS NULL OR TRIM(#{keyword}) = ''
                   OR s.name LIKE CONCAT('%', TRIM(#{keyword}), '%')
                   OR a.name LIKE CONCAT('%', TRIM(#{keyword}), '%')
                   OR COALESCE(al.name, s.album) LIKE CONCAT('%', TRIM(#{keyword}), '%')
                   OR s.style LIKE CONCAT('%', TRIM(#{keyword}), '%'))
            <if test="excludeIds != null and excludeIds.size() > 0">
              AND s.id NOT IN
              <foreach collection="excludeIds" item="excludedId" open="(" separator="," close=")">#{excludedId}</foreach>
            </if>
            ORDER BY s.id
            LIMIT #{limit}
            </script>
            """)
    List<AgentSongVO> findSongsExcluding(@Param("keyword") String keyword,
                                         @Param("artistName") String artistName,
                                         @Param("style") String style,
                                         @Param("excludeIds") List<Long> excludeIds,
                                         @Param("limit") int limit);

    /** 随机推荐；仅用于“随便来点歌”这类无明确条件的请求。 */
    @Select("""
            SELECT s.id AS songId,
                   s.name AS songName,
                   a.name AS artistName,
                   COALESCE(al.name, s.album) AS album,
                   s.style AS style,
                   s.duration AS duration,
                   s.cover_url AS coverUrl,
                   s.audio_url AS audioUrl
            FROM tb_song s
                     LEFT JOIN tb_artist a ON s.artist_id = a.id
                     LEFT JOIN tb_album al ON s.album_id = al.id
            ORDER BY RAND()
            LIMIT #{limit}
            """)
    List<AgentSongVO> findRandomSongs(@Param("limit") int limit);

    /** 按主键批量取歌曲，用于渲染待确认预览与校验写操作的目标资源。 */
    @Select("""
            <script>
            SELECT s.id AS songId,
                   s.name AS songName,
                   a.name AS artistName,
                   COALESCE(al.name, s.album) AS album,
                   s.style AS style,
                   s.duration AS duration,
                   s.cover_url AS coverUrl,
                   s.audio_url AS audioUrl
            FROM tb_song s
                     LEFT JOIN tb_artist a ON s.artist_id = a.id
                     LEFT JOIN tb_album al ON s.album_id = al.id
            WHERE s.id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY s.id
            </script>
            """)
    List<AgentSongVO> findSongsByIds(@Param("ids") List<Long> ids);
}
