package cn.edu.seig.vibemusic.service;

import cn.edu.seig.vibemusic.mapper.MediaReferenceMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 引用核对 SQL 的验收：判定"这个对象还有没有人用"必须数得全、数不错。
 *
 * <p>这份测试钉住的是一件用真实数据才发现的事：历史数据里同一对象的主机名既有
 * {@code localhost} 也有 {@code 127.0.0.1}（实测两列并存），所以引用核对必须用
 * "URL 以对象键结尾"（RIGHT 谓词）而不是"完整 URL 相等"——后者会把另一种主机名的
 * 引用漏掉，引用数被误判为 0，对象随即被误删。这是 Phase C 里最危险的一类静默损坏。
 *
 * <p>同时钉住"不过滤可见性"：停用歌手、草稿专辑、私有歌单、非自定义默认封面都算有效引用，
 * 少算一个引用就可能删掉别人还在用的文件。
 */
@SpringJUnitConfig(MediaReferenceCheckTest.Config.class)
class MediaReferenceCheckTest {

    private static final String KEY = "songCovers/642adf1e-shared-blob";

    @org.springframework.beans.factory.annotation.Autowired
    MediaReferenceMapper mapper;
    JdbcTemplate jdbc;

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(Config.dataSource());
        jdbc.execute("DROP TABLE IF EXISTS tb_song");
        jdbc.execute("DROP TABLE IF EXISTS tb_album");
        jdbc.execute("DROP TABLE IF EXISTS tb_playlist");
        jdbc.execute("DROP TABLE IF EXISTS tb_artist");
        jdbc.execute("DROP TABLE IF EXISTS tb_user");
        jdbc.execute("DROP TABLE IF EXISTS tb_banner");
        jdbc.execute("CREATE TABLE tb_song(id BIGINT PRIMARY KEY, cover_url VARCHAR(255), audio_url VARCHAR(255))");
        jdbc.execute("CREATE TABLE tb_album(id BIGINT PRIMARY KEY, cover_url VARCHAR(255), status VARCHAR(16) DEFAULT 'DRAFT')");
        jdbc.execute("CREATE TABLE tb_playlist(id BIGINT PRIMARY KEY, cover_url VARCHAR(255), cover_is_custom BOOLEAN DEFAULT FALSE, is_public BOOLEAN DEFAULT TRUE)");
        jdbc.execute("CREATE TABLE tb_artist(id BIGINT PRIMARY KEY, avatar VARCHAR(255), status VARCHAR(20) DEFAULT 'DISABLED')");
        jdbc.execute("CREATE TABLE tb_user(id BIGINT PRIMARY KEY, user_avatar VARCHAR(255))");
        jdbc.execute("CREATE TABLE tb_banner(id BIGINT PRIMARY KEY, banner_url VARCHAR(255))");
    }

    @Test
    @DisplayName("七类引用各自单独存在时都能被检出，总数等于引用行数")
    void countsEachOfTheSevenReferenceKinds() {
        String url = "http://localhost:9005/vibe-music-data/" + KEY;
        jdbc.update("INSERT INTO tb_song(id, cover_url) VALUES (1, ?)", url);
        jdbc.update("INSERT INTO tb_song(id, audio_url) VALUES (2, ?)", url);
        jdbc.update("INSERT INTO tb_album(id, cover_url) VALUES (1, ?)", url);
        jdbc.update("INSERT INTO tb_playlist(id, cover_url, cover_is_custom) VALUES (1, ?, TRUE)", url);
        jdbc.update("INSERT INTO tb_artist(id, avatar) VALUES (1, ?)", url);
        jdbc.update("INSERT INTO tb_user(id, user_avatar) VALUES (1, ?)", url);
        jdbc.update("INSERT INTO tb_banner(id, banner_url) VALUES (1, ?)", url);

        assertEquals(7L, mapper.countReferences(KEY));
    }

    @Test
    @DisplayName("同一类引用出现多行时逐行累计（共享对象正是靠它活下来）")
    void sumsMultipleRowsWithinTheSameTable() {
        jdbc.update("INSERT INTO tb_song(id, cover_url) VALUES (1, ?)", "http://localhost:9005/vibe-music-data/" + KEY);
        jdbc.update("INSERT INTO tb_song(id, cover_url) VALUES (2, ?)", "http://127.0.0.1:9005/vibe-music-data/" + KEY);
        jdbc.update("INSERT INTO tb_album(id, cover_url) VALUES (1, ?)", "http://localhost:9005/vibe-music-data/" + KEY);

        assertEquals(3L, mapper.countReferences(KEY));
    }

    @Test
    @DisplayName("对象键相同、主机名不同（localhost 与 127.0.0.1）都算同一对象的引用")
    void matchesObjectKeyRegardlessOfHostNameVariant() {
        // 两种主机名各插一行，与真实数据分布一致
        jdbc.update("INSERT INTO tb_song(id, cover_url) VALUES (1, ?)", "http://localhost:9005/vibe-music-data/" + KEY);
        jdbc.update("INSERT INTO tb_song(id, cover_url) VALUES (2, ?)", "http://127.0.0.1:9005/vibe-music-data/" + KEY);
        jdbc.update("INSERT INTO tb_song(id, cover_url) VALUES (3, NULL)");

        assertEquals(2L, mapper.countReferences(KEY));
    }

    @Test
    @DisplayName("停用歌手、草稿专辑、私有歌单、非自定义默认封面的引用同样计入（不过滤可见性）")
    void countsReferencesWithoutVisibilityFiltering() {
        String url = "http://localhost:9005/vibe-music-data/" + KEY;
        jdbc.update("INSERT INTO tb_artist(id, avatar, status) VALUES (1, ?, 'DISABLED')", url);
        jdbc.update("INSERT INTO tb_album(id, cover_url, status) VALUES (1, ?, 'DRAFT')", url);
        jdbc.update("INSERT INTO tb_playlist(id, cover_url, cover_is_custom, is_public) VALUES (1, ?, FALSE, FALSE)", url);

        assertEquals(3L, mapper.countReferences(KEY));
    }

    @Test
    @DisplayName("更长的同前缀对象不算引用；目录前缀不同的同后缀键被保守地计入（多算→漏删，方向安全）")
    void treatsSuffixCollisionsConservatively() {
        // ".../KEY-suffix.jpg" 不以 KEY 结尾（末尾 len(KEY) 个字符 ≠ KEY），不计
        jdbc.update("INSERT INTO tb_song(id, cover_url) VALUES (1, ?)",
                "http://localhost:9005/vibe-music-data/" + KEY + "-suffix.jpg");
        // ".../other/KEY" 以 KEY 结尾，RIGHT 谓词会计入。方向是多算引用 → 保留对象，
        // 宁可漏删也不能误删；真实键含 UUID，后缀撞车几乎不可能发生。
        jdbc.update("INSERT INTO tb_song(id, cover_url) VALUES (2, ?)",
                "http://localhost:9005/vibe-music-data/other/" + KEY);

        assertEquals(1L, mapper.countReferences(KEY));
    }

    @Test
    @DisplayName("七类全部不命中时引用数为 0，允许删除")
    void returnsZeroWhenNothingReferencesTheObject() {
        jdbc.update("INSERT INTO tb_song(id, cover_url) VALUES (1, 'http://localhost:9005/vibe-music-data/other/x.jpg')");

        assertEquals(0L, mapper.countReferences(KEY));
        assertEquals(0L, mapper.countReferences("songs/00000000-0000-0000-0000-000000000000-gone.mp3"));
    }

    @Test
    @DisplayName("空键防护在 service 层：空串/空白的引用数恒为 0，绝不落库匹配")
    void serviceGuardsBlankKeysBeforeTouchingTheDatabase() {
        jdbc.update("INSERT INTO tb_song(id, cover_url) VALUES (1, 'http://localhost:9005/vibe-music-data/" + KEY + "')");

        MediaReferenceService service = new MediaReferenceService(mapper);
        assertEquals(0L, service.countReferences(""));
        assertEquals(0L, service.countReferences("   "));
        assertEquals(0L, service.countReferences(null));
        // mapper 层对 null 键同样返回 0（CHAR_LENGTH(NULL) → RIGHT(x, NULL) → NULL 不成立）
        assertEquals(0L, mapper.countReferences(null));
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        private static final JdbcDataSource SOURCE = new JdbcDataSource();

        Config() {
            SOURCE.setURL("jdbc:h2:mem:media_reference;MODE=MySQL;DB_CLOSE_DELAY=-1");
        }

        static DataSource dataSource() {
            return SOURCE;
        }

        @Bean
        MediaReferenceMapper mediaReferenceMapper() throws Exception {
            MybatisConfiguration config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            config.addMapper(MediaReferenceMapper.class);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource());
            factory.setConfiguration(config);
            SqlSessionFactory sqlSessionFactory = factory.getObject();
            return new SqlSessionTemplate(sqlSessionFactory).getMapper(MediaReferenceMapper.class);
        }
    }
}
