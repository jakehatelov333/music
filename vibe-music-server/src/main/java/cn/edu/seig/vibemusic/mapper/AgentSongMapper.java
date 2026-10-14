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
 *
 * <p><b>展示串一律读主表冗余列 {@code s.artist_names}</b>（多歌手已按 display_order 拼好），
 * 因此这里不再 JOIN {@code tb_artist}。少一个 JOIN 意味着分页 COUNT 不可能被关联行放大。
 *
 * <p><b>但「按歌手筛选」不能用 {@code artist_names LIKE}</b>：那是在派生展示串上做子串匹配，
 * 既会被跨分隔符的片段误命中，也表达不了「这位歌手参与了这首歌」的归属语义。
 * 因此凡是歌手维度的筛选，参数一律是调用方（{@code AgentArtistResolver}）解析好的
 * {@code artistIds}，SQL 里用 {@code EXISTS} 查权威关系表 {@code tb_song_artist}。
 * 与关键词召回（{@code findSongsByKeyword}）不同：那里 {@code artist_names LIKE} 只是召回手段，保留。
 */
@Mapper
public interface AgentSongMapper {

    /**
     * 分页插件使用同一组筛选条件计算总数；稳定 ID 排序避免翻页重复。
     *
     * <p>{@code artistIds} 为空/为 null 时不加歌手条件（调用方在「给了歌手名但解析不到」时
     * 应直接返回空页，而不是把空集合当成「不限制」）。
     */
    @Select("""
            <script>
            SELECT s.id AS songId, s.name AS songName, s.artist_names AS artistName,
                   COALESCE(al.name, s.album) AS album, s.style AS style,
                   s.duration AS duration, s.cover_url AS coverUrl, s.audio_url AS audioUrl
            FROM tb_song s
            LEFT JOIN tb_album al ON s.album_id = al.id
            WHERE (#{keyword} IS NULL OR s.name LIKE CONCAT('%', #{keyword}, '%')
                   OR s.artist_names LIKE CONCAT('%', #{keyword}, '%')
                   OR COALESCE(al.name, s.album) LIKE CONCAT('%', #{keyword}, '%')
                   OR s.style LIKE CONCAT('%', #{keyword}, '%'))
              AND (#{style} IS NULL OR s.style LIKE CONCAT('%', #{style}, '%'))
            <if test="artistIds != null and artistIds.size() > 0">
              AND EXISTS (SELECT 1 FROM tb_song_artist sa
                          WHERE sa.song_id = s.id AND sa.artist_id IN
                          <foreach collection="artistIds" item="aid" open="(" separator="," close=")">#{aid}</foreach>)
            </if>
              AND NOT EXISTS (
                    SELECT 1 FROM tb_song_artist sa2
                    JOIN tb_artist a2 ON a2.id = sa2.artist_id
                    WHERE sa2.song_id = s.id AND a2.status &lt;&gt; 'ACTIVE')
            ORDER BY s.id
            </script>
            """)
    com.baomidou.mybatisplus.core.metadata.IPage<AgentSongVO> searchPage(
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<AgentSongVO> page,
            @Param("keyword") String keyword, @Param("artistIds") List<Long> artistIds,
            @Param("style") String style);

    /**
     * 组合筛选：歌名 / 歌手归属 / 专辑 / 风格，全部为可选条件（null 或空串表示不限制）。
     * 条件之间是 AND，用于「歌手 + 风格」这类精确组合检索。
     *
     * <p>{@code artistIds} 由调用方解析歌手名得到，走 {@code EXISTS} 关联表，语义是「歌手归属」。
     */
    @Select("""
            <script>
            SELECT s.id AS songId,
                   s.name AS songName,
                   s.artist_names AS artistName,
                   COALESCE(al.name, s.album) AS album,
                   s.style AS style,
                   s.duration AS duration,
                   s.cover_url AS coverUrl,
                   s.audio_url AS audioUrl
            FROM tb_song s
                     LEFT JOIN tb_album al ON s.album_id = al.id
            WHERE (#{songName} IS NULL OR TRIM(#{songName}) = '' OR s.name LIKE CONCAT('%', TRIM(#{songName}), '%'))
              AND (#{album} IS NULL OR TRIM(#{album}) = '' OR COALESCE(al.name, s.album) LIKE CONCAT('%', TRIM(#{album}), '%'))
              AND (#{style} IS NULL OR TRIM(#{style}) = '' OR s.style LIKE CONCAT('%', TRIM(#{style}), '%'))
            <if test="artistIds != null and artistIds.size() > 0">
              AND EXISTS (SELECT 1 FROM tb_song_artist sa
                          WHERE sa.song_id = s.id AND sa.artist_id IN
                          <foreach collection="artistIds" item="aid" open="(" separator="," close=")">#{aid}</foreach>)
            </if>
              AND NOT EXISTS (
                    SELECT 1 FROM tb_song_artist sa2
                    JOIN tb_artist a2 ON a2.id = sa2.artist_id
                    WHERE sa2.song_id = s.id AND a2.status &lt;&gt; 'ACTIVE')
            ORDER BY s.id
            LIMIT #{limit}
            </script>
            """)
    List<AgentSongVO> findSongs(@Param("songName") String songName,
                               @Param("artistIds") List<Long> artistIds,
                               @Param("album") String album,
                               @Param("style") String style,
                               @Param("limit") int limit);

    /**
     * 按<b>歌手归属</b>精确检索：先由 {@code AgentSongQueryService} 把歌手名解析成
     * {@code tb_artist} 的 id 集合，再用 {@code EXISTS} 查关系表。
     *
     * <p>为什么不用 {@code artist_names LIKE}：展示串只是召回手段，无法区分
     * 「这位歌手参与了这首歌」与「名字恰好是另一个名字的子串」。合作歌曲在
     * {@code tb_song_artist} 里对每位参与者都有一行，所以 EXISTS 既不漏召回也不误召回。
     *
     * <p>{@code artistIds} 为空集合时由调用方短路，不会走到这里。
     */
    @Select("""
            <script>
            SELECT s.id AS songId,
                   s.name AS songName,
                   s.artist_names AS artistName,
                   COALESCE(al.name, s.album) AS album,
                   s.style AS style,
                   s.duration AS duration,
                   s.cover_url AS coverUrl,
                   s.audio_url AS audioUrl
            FROM tb_song s
                     LEFT JOIN tb_album al ON s.album_id = al.id
            WHERE EXISTS (
                      SELECT 1 FROM tb_song_artist sa
                      WHERE sa.song_id = s.id
                        AND sa.artist_id IN
                        <foreach collection="artistIds" item="aid" open="(" separator="," close=")">#{aid}</foreach>
                  )
              <if test="style != null and style.trim() != ''">
              AND s.style LIKE CONCAT('%', TRIM(#{style}), '%')
              </if>
              AND NOT EXISTS (
                    SELECT 1 FROM tb_song_artist sa2
                    JOIN tb_artist a2 ON a2.id = sa2.artist_id
                    WHERE sa2.song_id = s.id AND a2.status &lt;&gt; 'ACTIVE')
            ORDER BY s.id
            LIMIT #{limit}
            </script>
            """)
    List<AgentSongVO> findSongsByArtistIds(@Param("artistIds") List<Long> artistIds,
                                           @Param("style") String style,
                                           @Param("limit") int limit);

    /**
     * 单一关键词的宽泛检索：歌名 / 歌手 / 专辑 / 风格之间是 <b>OR</b>。
     * 用户说“搜一下夜色”时，命中歌名或命中歌手都应该算命中，用 AND 会一个都搜不到。
     */
    @Select("""
            SELECT s.id AS songId,
                   s.name AS songName,
                   s.artist_names AS artistName,
                   COALESCE(al.name, s.album) AS album,
                   s.style AS style,
                   s.duration AS duration,
                   s.cover_url AS coverUrl,
                   s.audio_url AS audioUrl
            FROM tb_song s
                     LEFT JOIN tb_album al ON s.album_id = al.id
            WHERE (s.name LIKE CONCAT('%', TRIM(#{keyword}), '%')
               OR s.artist_names LIKE CONCAT('%', TRIM(#{keyword}), '%')
               OR COALESCE(al.name, s.album) LIKE CONCAT('%', TRIM(#{keyword}), '%')
               OR s.style LIKE CONCAT('%', TRIM(#{keyword}), '%'))
              AND NOT EXISTS (
                    SELECT 1 FROM tb_song_artist sa
                    JOIN tb_artist a ON a.id = sa.artist_id
                    WHERE sa.song_id = s.id AND a.status <> 'ACTIVE')
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
     *
     * <p>{@code artistIds} 同样是解析后的歌手归属（{@code EXISTS} 关联表），不是展示串匹配 ——
     * 否则「排除后仍能取到新歌」的语义会被不精确的歌手筛选放大成误判。
     *
     * <p><b>结构化排除（用户长期偏好）与历史排除是两件事，都必须在 SQL 层、{@code LIMIT} 之前：</b>
     * <ul>
     *     <li>{@code excludeIds}：本会话近期歌单已用过的歌曲；</li>
     *     <li>{@code excludedArtistIds}：用户手动排除的歌手（权威 {@code artist_id}）。
     *         用 {@code EXISTS} 查 {@code tb_song_artist}，因此合作曲只要有一位演唱者被排除就整首排除；</li>
     *     <li>{@code excludedStyleNames}：用户手动排除的风格。{@code tb_song.style} 是逗号分隔多值串
     *         （如 {@code 欧美流行,电子}），必须按 token 精确比对 —— 既不能整串相等，也不能子串误命中
     *         （例如排除「流行」不得命中「欧美流行」）。这里先把风格串规范成
     *         {@code ,token1,token2,} 再用 {@code LIKE '%,风格,%'} 匹配，兼容 MySQL 与 H2(MODE=MySQL)，
     *         刻意不使用只有 MySQL 才有的 {@code FIND_IN_SET}。</li>
     * </ul>
     */
    @Select("""
            <script>
            SELECT s.id AS songId,
                   s.name AS songName,
                   s.artist_names AS artistName,
                   COALESCE(al.name, s.album) AS album,
                   s.style AS style,
                   s.duration AS duration,
                   s.cover_url AS coverUrl,
                   s.audio_url AS audioUrl
            FROM tb_song s
                     LEFT JOIN tb_album al ON s.album_id = al.id
            WHERE (#{style} IS NULL OR TRIM(#{style}) = '' OR s.style LIKE CONCAT('%', TRIM(#{style}), '%'))
              AND (#{keyword} IS NULL OR TRIM(#{keyword}) = ''
                   OR s.name LIKE CONCAT('%', TRIM(#{keyword}), '%')
                   OR s.artist_names LIKE CONCAT('%', TRIM(#{keyword}), '%')
                   OR COALESCE(al.name, s.album) LIKE CONCAT('%', TRIM(#{keyword}), '%')
                   OR s.style LIKE CONCAT('%', TRIM(#{keyword}), '%'))
            <if test="artistIds != null and artistIds.size() > 0">
              AND EXISTS (SELECT 1 FROM tb_song_artist sa
                          WHERE sa.song_id = s.id AND sa.artist_id IN
                          <foreach collection="artistIds" item="aid" open="(" separator="," close=")">#{aid}</foreach>)
            </if>
            <if test="excludeIds != null and excludeIds.size() > 0">
              AND s.id NOT IN
              <foreach collection="excludeIds" item="excludedId" open="(" separator="," close=")">#{excludedId}</foreach>
            </if>
            <if test="excludedArtistIds != null and excludedArtistIds.size() > 0">
              AND NOT EXISTS (SELECT 1 FROM tb_song_artist sa3
                              WHERE sa3.song_id = s.id AND sa3.artist_id IN
                              <foreach collection="excludedArtistIds" item="eaid" open="(" separator="," close=")">#{eaid}</foreach>)
            </if>
            <if test="excludedStyleNames != null and excludedStyleNames.size() > 0">
              AND (s.style IS NULL OR NOT (
              <foreach collection="excludedStyleNames" item="exStyle" separator=" OR ">
                  CONCAT(',', REPLACE(REPLACE(REPLACE(COALESCE(s.style, ''), '，', ','), ', ', ','), ' ,', ','), ',') LIKE CONCAT('%,', #{exStyle}, ',%')
              </foreach>
              ))
            </if>
              AND NOT EXISTS (
                    SELECT 1 FROM tb_song_artist sa2
                    JOIN tb_artist a2 ON a2.id = sa2.artist_id
                    WHERE sa2.song_id = s.id AND a2.status &lt;&gt; 'ACTIVE')
            ORDER BY s.id
            LIMIT #{limit}
            </script>
            """)
    List<AgentSongVO> findSongsExcluding(@Param("keyword") String keyword,
                                         @Param("artistIds") List<Long> artistIds,
                                         @Param("style") String style,
                                         @Param("excludeIds") List<Long> excludeIds,
                                         @Param("excludedArtistIds") List<Long> excludedArtistIds,
                                         @Param("excludedStyleNames") List<String> excludedStyleNames,
                                         @Param("limit") int limit);

    /**
     * 随机推荐；仅用于“随便来点歌”这类无明确条件的请求。
     *
     * <p>排除语义与 {@link #findSongsExcluding} 完全一致（同一套 {@code EXISTS} + token 精确比对），
     * 因为随机推荐正是「无明确条件」的推荐路径，最容易把用户已排除的歌手/风格又推回来。
     */
    @Select("""
            <script>
            SELECT s.id AS songId,
                   s.name AS songName,
                   s.artist_names AS artistName,
                   COALESCE(al.name, s.album) AS album,
                   s.style AS style,
                   s.duration AS duration,
                   s.cover_url AS coverUrl,
                   s.audio_url AS audioUrl
            FROM tb_song s
                     LEFT JOIN tb_album al ON s.album_id = al.id
            WHERE NOT EXISTS (
                    SELECT 1 FROM tb_song_artist sa
                    JOIN tb_artist a ON a.id = sa.artist_id
                    WHERE sa.song_id = s.id AND a.status &lt;&gt; 'ACTIVE')
            <if test="excludedArtistIds != null and excludedArtistIds.size() > 0">
              AND NOT EXISTS (SELECT 1 FROM tb_song_artist sa3
                              WHERE sa3.song_id = s.id AND sa3.artist_id IN
                              <foreach collection="excludedArtistIds" item="eaid" open="(" separator="," close=")">#{eaid}</foreach>)
            </if>
            <if test="excludedStyleNames != null and excludedStyleNames.size() > 0">
              AND (s.style IS NULL OR NOT (
              <foreach collection="excludedStyleNames" item="exStyle" separator=" OR ">
                  CONCAT(',', REPLACE(REPLACE(REPLACE(COALESCE(s.style, ''), '，', ','), ', ', ','), ' ,', ','), ',') LIKE CONCAT('%,', #{exStyle}, ',%')
              </foreach>
              ))
            </if>
            ORDER BY RAND()
            LIMIT #{limit}
            </script>
            """)
    List<AgentSongVO> findRandomSongs(@Param("limit") int limit,
                                      @Param("excludedArtistIds") List<Long> excludedArtistIds,
                                      @Param("excludedStyleNames") List<String> excludedStyleNames);

    /** 按主键批量取歌曲，用于渲染待确认预览与校验写操作的目标资源。 */
    @Select("""
            <script>
            SELECT s.id AS songId,
                   s.name AS songName,
                   s.artist_names AS artistName,
                   COALESCE(al.name, s.album) AS album,
                   s.style AS style,
                   s.duration AS duration,
                   s.cover_url AS coverUrl,
                   s.audio_url AS audioUrl
            FROM tb_song s
                     LEFT JOIN tb_album al ON s.album_id = al.id
            WHERE s.id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
              AND NOT EXISTS (
                    SELECT 1 FROM tb_song_artist sa
                    JOIN tb_artist a ON a.id = sa.artist_id
                    WHERE sa.song_id = s.id AND a.status &lt;&gt; 'ACTIVE')
            ORDER BY s.id
            </script>
            """)
    List<AgentSongVO> findSongsByIds(@Param("ids") List<Long> ids);
}
