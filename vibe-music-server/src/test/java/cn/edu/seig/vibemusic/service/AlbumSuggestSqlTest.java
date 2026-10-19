package cn.edu.seig.vibemusic.service;

import cn.edu.seig.vibemusic.enumeration.ArtistStatusEnum;
import cn.edu.seig.vibemusic.mapper.AlbumMapper;
import cn.edu.seig.vibemusic.model.vo.AlbumSuggestVO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理端专辑联想 SQL 的验收。
 *
 * <p>这份测试盯的是<b>与用户端搜索的三处刻意差异</b>，每一处都对应一个真实会让人困惑的场景：
 * <ul>
 *   <li><b>停用歌手的专辑必须可见</b>：用户端固定过滤 {@code ar.status='ACTIVE'}，
 *       若管理端照搬，会出现「明明有这张专辑，输入名字却搜不到」；</li>
 *   <li><b>草稿专辑必须可见</b>：管理员刚建的草稿，用户端看不见是应该的，
 *       管理端看不见就是 bug；</li>
 *   <li><b>当前歌手优先</b>：上传歌曲时最可能的意图是「给这位歌手已有的专辑加歌」，
 *       若同名专辑属于别的歌手却排在前面，很容易选错。</li>
 * </ul>
 */
@SpringJUnitConfig(AlbumSuggestSqlTest.Config.class)
class AlbumSuggestSqlTest {

    @org.springframework.beans.factory.annotation.Autowired
    AlbumMapper mapper;

    JdbcTemplate jdbc;

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(Config.dataSource());
        jdbc.execute("DROP TABLE IF EXISTS tb_song");
        jdbc.execute("DROP TABLE IF EXISTS tb_album");
        jdbc.execute("DROP TABLE IF EXISTS tb_artist");
        jdbc.execute("CREATE TABLE tb_artist(" +
                "id BIGINT PRIMARY KEY, name VARCHAR(255), status VARCHAR(20) DEFAULT 'ACTIVE')");
        jdbc.execute("CREATE TABLE tb_album(" +
                "id BIGINT PRIMARY KEY, artist_id BIGINT, name VARCHAR(255), " +
                "cover_url VARCHAR(255), release_date DATE, status VARCHAR(20) DEFAULT 'PUBLISHED')");
        jdbc.execute("CREATE TABLE tb_song(" +
                "id BIGINT PRIMARY KEY, album_id BIGINT, cover_url VARCHAR(255), release_time DATE)");
    }

    // ------------------------------------------------------------------ 基础匹配

    @Test
    @DisplayName("按关键词做包含匹配，返回专辑名与歌手名")
    void matchesByKeyword() {
        insertArtist(1L, "周杰伦", "ACTIVE");
        insertAlbum(100L, 1L, "太阳之子", "2026-01-01");
        insertAlbum(101L, 1L, "十一月的萧邦", "2005-11-01");

        List<AlbumSuggestVO> result = mapper.suggestAlbumsForAdmin("太阳", null, 10);

        assertEquals(1, result.size());
        assertEquals("太阳之子", result.get(0).getAlbumName());
        assertEquals("周杰伦", result.get(0).getArtistName());
        assertEquals(1L, result.get(0).getArtistId());
    }

    @Test
    @DisplayName("歌手名也能命中")
    void matchesByArtistName() {
        insertArtist(1L, "周杰伦", "ACTIVE");
        insertAlbum(100L, 1L, "太阳之子", "2026-01-01");

        List<AlbumSuggestVO> result = mapper.suggestAlbumsForAdmin("周杰", null, 10);

        assertEquals(1, result.size());
        assertEquals("太阳之子", result.get(0).getAlbumName());
    }

    /** 歌曲数用于排序，也是管理员判断「这张专辑是不是我要找的」的依据。 */
    @Test
    @DisplayName("返回歌曲数")
    void returnsSongCount() {
        insertArtist(1L, "周杰伦", "ACTIVE");
        insertAlbum(100L, 1L, "太阳之子", "2026-01-01");
        for (long i = 0; i < 5; i++) {
            jdbc.update("INSERT INTO tb_song(id, album_id) VALUES (?, 100)", 1000 + i);
        }

        List<AlbumSuggestVO> result = mapper.suggestAlbumsForAdmin("太阳", null, 10);

        assertEquals(5L, result.get(0).getSongCount());
    }

    // ------------------------------------------------------------------ 与用户端的三处差异

    /**
     * 停用歌手的专辑必须出现在管理端联想里。
     *
     * <p>用户端 {@code searchAlbums} 固定 {@code ar.status='ACTIVE'}；照搬到管理端，
     * 管理员输入已停用歌手的专辑名会得到空结果，而那张专辑在数据库里真实存在。
     */
    @Test
    @DisplayName("停用歌手的专辑仍然可见，并带回 DISABLED 状态供前端标注")
    void includesDisabledArtistAlbums() {
        insertArtist(1L, "银临", "DISABLED");
        insertAlbum(100L, 1L, "山色有无中", "2020-01-01");

        List<AlbumSuggestVO> result = mapper.suggestAlbumsForAdmin("山色", null, 10);

        assertEquals(1, result.size(), "停用歌手的专辑不能被过滤掉");
        assertEquals(ArtistStatusEnum.DISABLED.getStatus(), result.get(0).getArtistStatus());
    }

    @Test
    @DisplayName("启用歌手的专辑带回 ACTIVE 状态")
    void includesActiveArtistStatus() {
        insertArtist(1L, "周杰伦", "ACTIVE");
        insertAlbum(100L, 1L, "太阳之子", "2026-01-01");

        List<AlbumSuggestVO> result = mapper.suggestAlbumsForAdmin("太阳", null, 10);

        assertEquals(ArtistStatusEnum.ACTIVE.getStatus(), result.get(0).getArtistStatus());
    }

    /** 草稿专辑在用户端不可见是应该的，但管理端必须能看见。 */
    @Test
    @DisplayName("草稿专辑在管理端联想里可见")
    void includesDraftAlbums() {
        insertArtist(1L, "周杰伦", "ACTIVE");
        insertAlbum(100L, 1L, "未发布专辑", "2026-05-01");
        jdbc.update("UPDATE tb_album SET status = 'DRAFT' WHERE id = 100");

        List<AlbumSuggestVO> result = mapper.suggestAlbumsForAdmin("未发布", null, 10);

        assertEquals(1, result.size(), "草稿专辑不能被过滤掉");
    }

    // ------------------------------------------------------------------ 排序

    /**
     * 传了 artistId 时，该歌手的专辑排最前。
     *
     * <p>构造的是真实会遇到的场景：库里有两个歌手各有一张名字含「太阳」的专辑，
     * 当前选中的是周杰伦，那么周杰伦那张必须排第一 —— 否则管理员很容易选到别人的专辑。
     */
    @Test
    @DisplayName("当前歌手的专辑优先于其它歌手")
    void prioritizesCurrentArtist() {
        insertArtist(1L, "周杰伦", "ACTIVE");
        insertArtist(2L, "某新人", "ACTIVE");
        insertAlbum(100L, 2L, "太阳之子", "2026-01-01");
        insertAlbum(101L, 1L, "太阳之子", "2025-06-01");

        List<AlbumSuggestVO> result = mapper.suggestAlbumsForAdmin("太阳", 1L, 10);

        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).getArtistId(), "当前歌手（周杰伦）的专辑应排第一");
        assertEquals(101L, result.get(0).getAlbumId());
    }

    /** 不传 artistId 时退化为「专辑名完全相等 > 前缀 > 包含」。 */
    @Test
    @DisplayName("不传 artistId 时按名称匹配精度排序：完全相等 > 前缀 > 包含")
    void ordersByMatchPrecisionWithoutArtist() {
        insertArtist(1L, "A", "ACTIVE");
        insertAlbum(100L, 1L, "太阳", "2020-01-01");          // 完全相等
        insertAlbum(101L, 1L, "太阳之子", "2020-01-01");       // 前缀
        insertAlbum(102L, 1L, "我的太阳", "2020-01-01");       // 包含

        List<AlbumSuggestVO> result = mapper.suggestAlbumsForAdmin("太阳", null, 10);

        assertEquals(3, result.size());
        assertEquals("太阳", result.get(0).getAlbumName());
        assertEquals("太阳之子", result.get(1).getAlbumName());
        assertEquals("我的太阳", result.get(2).getAlbumName());
    }

    /** 同一档内按歌曲数倒序：歌多的专辑更可能是目标。 */
    @Test
    @DisplayName("同档内按歌曲数倒序")
    void ordersBySongCountWithinSameTier() {
        insertArtist(1L, "A", "ACTIVE");
        insertAlbum(100L, 1L, "精选集甲", "2020-01-01");
        insertAlbum(101L, 1L, "精选集乙", "2020-01-01");
        for (long i = 0; i < 3; i++) {
            jdbc.update("INSERT INTO tb_song(id, album_id) VALUES (?, 100)", 2000 + i);
        }
        for (long i = 0; i < 8; i++) {
            jdbc.update("INSERT INTO tb_song(id, album_id) VALUES (?, 101)", 3000 + i);
        }

        List<AlbumSuggestVO> result = mapper.suggestAlbumsForAdmin("精选集", null, 10);

        assertEquals(2, result.size());
        assertEquals("精选集乙", result.get(0).getAlbumName(), "歌多的应排前面");
    }

    // ------------------------------------------------------------------ 边界

    @Test
    @DisplayName("limit 生效")
    void respectsLimit() {
        insertArtist(1L, "A", "ACTIVE");
        for (long i = 0; i < 15; i++) {
            insertAlbum(100L + i, 1L, "专辑" + i, "2020-01-01");
        }

        List<AlbumSuggestVO> result = mapper.suggestAlbumsForAdmin("专辑", null, 5);

        assertEquals(5, result.size());
    }

    /** 同名专辑属于不同歌手时，两条都要返回 —— 它们是不同的专辑实体。 */
    @Test
    @DisplayName("同名专辑属于不同歌手时分别返回")
    void returnsSameNameAlbumsOfDifferentArtists() {
        insertArtist(1L, "周杰伦", "ACTIVE");
        insertArtist(2L, "某翻唱歌手", "ACTIVE");
        insertAlbum(100L, 1L, "太阳之子", "2026-01-01");
        insertAlbum(101L, 2L, "太阳之子", "2024-01-01");

        List<AlbumSuggestVO> result = mapper.suggestAlbumsForAdmin("太阳之子", null, 10);

        assertEquals(2, result.size(), "两张不同的专辑都要返回，由前端显示歌手区分");
        assertTrue(result.stream().anyMatch(a -> a.getArtistId() == 1L));
        assertTrue(result.stream().anyMatch(a -> a.getArtistId() == 2L));
    }

    @Test
    @DisplayName("没有匹配时返回空列表，不报错")
    void returnsEmptyWhenNothingMatches() {
        insertArtist(1L, "周杰伦", "ACTIVE");
        insertAlbum(100L, 1L, "太阳之子", "2026-01-01");

        List<AlbumSuggestVO> result = mapper.suggestAlbumsForAdmin("完全不存在的名字", null, 10);

        assertTrue(result.isEmpty());
    }

    /** 专辑封面可能为空（专辑没传封面但歌曲有），此时用歌曲封面兜底。 */
    @Test
    @DisplayName("专辑无封面时用歌曲封面兜底")
    void fallsBackToSongCover() {
        insertArtist(1L, "周杰伦", "ACTIVE");
        insertAlbum(100L, 1L, "太阳之子", "2026-01-01");
        jdbc.update("INSERT INTO tb_song(id, album_id, cover_url) VALUES (1, 100, 'http://x/song-cover.jpg')");

        List<AlbumSuggestVO> result = mapper.suggestAlbumsForAdmin("太阳", null, 10);

        assertEquals("http://x/song-cover.jpg", result.get(0).getCoverUrl());
    }

    // ------------------------------------------------------------------ 夹具

    private void insertArtist(long id, String name, String status) {
        jdbc.update("INSERT INTO tb_artist(id, name, status) VALUES (?, ?, ?)", id, name, status);
    }

    private void insertAlbum(long id, long artistId, String name, String releaseDate) {
        jdbc.update("INSERT INTO tb_album(id, artist_id, name, release_date) VALUES (?, ?, ?, ?)",
                id, artistId, name, releaseDate);
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        private static final JdbcDataSource SOURCE = new JdbcDataSource();

        Config() {
            SOURCE.setURL("jdbc:h2:mem:album_suggest;MODE=MySQL;DB_CLOSE_DELAY=-1");
        }

        static DataSource dataSource() {
            return SOURCE;
        }

        @Bean
        AlbumMapper albumMapper() throws Exception {
            MybatisConfiguration config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            config.addMapper(AlbumMapper.class);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource());
            factory.setConfiguration(config);
            SqlSessionFactory sqlSessionFactory = factory.getObject();
            return new SqlSessionTemplate(sqlSessionFactory).getMapper(AlbumMapper.class);
        }
    }
}
