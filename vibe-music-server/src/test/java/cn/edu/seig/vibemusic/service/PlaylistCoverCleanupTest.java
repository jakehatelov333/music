package cn.edu.seig.vibemusic.service;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.mapper.MediaCleanupTaskMapper;
import cn.edu.seig.vibemusic.mapper.MediaReferenceMapper;
import cn.edu.seig.vibemusic.mapper.PlaylistBindingMapper;
import cn.edu.seig.vibemusic.mapper.PlaylistCreateRequestMapper;
import cn.edu.seig.vibemusic.mapper.PlaylistMapper;
import cn.edu.seig.vibemusic.mapper.SongMapper;
import cn.edu.seig.vibemusic.model.entity.Playlist;
import cn.edu.seig.vibemusic.model.vo.PlaylistDetailVO;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.security.PlaylistAccessService;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.annotation.DbType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import javax.sql.DataSource;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 个人歌单封面清理的验收：替换/删除自定义封面要把<b>旧对象</b>登记进清理任务，默认封面绝不动。
 *
 * <p>这份测试钉住的是原来的 {@code afterCommit + 直接删} 写法留下的两个缺口：
 * <ol>
 *     <li>回滚后文件已经删掉了（外部系统不参与 MySQL 事务）；</li>
 *     <li>默认封面是歌曲的共享图，删歌单时把它删掉会连带弄坏歌曲。</li>
 * </ol>
 * 现在的契约是：只登记任务、由 worker 核对引用后决定删或保留，且只有"用户上传的封面"
 * （{@code cover_is_custom = true}）才有资格入队。
 */
@SpringJUnitConfig(PlaylistCoverCleanupTest.Config.class)
class PlaylistCoverCleanupTest {

    private static final String OLD_COVER = "http://127.0.0.1:9005/vibe-music-data/playlists/old-cover.jpg";
    private static final String NEW_COVER = "http://127.0.0.1:9005/vibe-music-data/playlists/new-cover.jpg";

    @Autowired PersonalPlaylistService personal;
    @Autowired PlaylistMapper playlists;
    @Autowired MinioService minio;
    @Autowired DataSource dataSource;
    JdbcTemplate jdbc;

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        Mockito.reset(minio);
        Mockito.when(minio.toObjectName(anyString())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            String marker = "/vibe-music-data/";
            int i = url.indexOf(marker);
            return i >= 0 ? url.substring(i + marker.length()) : url;
        });
        // 与生产同构的最小夹具：歌单、绑定、歌曲与清理任务表
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_user(id BIGINT PRIMARY KEY, username VARCHAR(255), user_avatar VARCHAR(255))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_song(id BIGINT PRIMARY KEY, name VARCHAR(255), album VARCHAR(255), album_id BIGINT, artist_id BIGINT, disc_number INT, track_number INT, lyric CLOB, lyric_source VARCHAR(32), lyric_status INT, lyric_updated_at TIMESTAMP, duration VARCHAR(20), style VARCHAR(255), cover_url VARCHAR(255), audio_url VARCHAR(255), release_time DATE)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_playlist(id BIGINT AUTO_INCREMENT PRIMARY KEY, title VARCHAR(255), cover_url VARCHAR(255), introduction VARCHAR(2000), style VARCHAR(255), owner_user_id BIGINT, is_public BOOLEAN DEFAULT TRUE, status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED', cover_is_custom BOOLEAN NOT NULL DEFAULT FALSE)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_playlist_binding(playlist_id BIGINT, song_id BIGINT, PRIMARY KEY(playlist_id,song_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_playlist_create_request(user_id BIGINT, request_key VARCHAR(64), request_hash CHAR(64), playlist_id BIGINT, PRIMARY KEY(user_id,request_key))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_comment(id BIGINT PRIMARY KEY, playlist_id BIGINT, type INT, content VARCHAR(255), create_time TIMESTAMP, like_count BIGINT, user_id BIGINT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_user_favorite(id BIGINT PRIMARY KEY, user_id BIGINT, playlist_id BIGINT, type INT, create_time TIMESTAMP)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS tb_media_cleanup_task (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    bucket VARCHAR(64) NOT NULL,
                    object_key VARCHAR(255) NOT NULL,
                    key_digest CHAR(64) NOT NULL,
                    source_type VARCHAR(32) NOT NULL,
                    source_ref VARCHAR(64) NOT NULL,
                    reason VARCHAR(64),
                    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                    attempts INT NOT NULL DEFAULT 0,
                    next_run_at TIMESTAMP NOT NULL,
                    lease_owner VARCHAR(64),
                    lease_expires_at TIMESTAMP,
                    generation BIGINT NOT NULL DEFAULT 0,
                    last_error VARCHAR(500),
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL,
                    CONSTRAINT uk_media_cleanup UNIQUE (source_type, source_ref, key_digest)
                )""");
        jdbc.update("DELETE FROM tb_media_cleanup_task");
        jdbc.update("DELETE FROM tb_playlist_create_request");
        jdbc.update("DELETE FROM tb_playlist_binding");
        jdbc.update("DELETE FROM tb_playlist");
        jdbc.update("DELETE FROM tb_song");
        jdbc.update("DELETE FROM tb_user");
        jdbc.update("INSERT INTO tb_user(id, username) VALUES (1, 'owner'), (2, 'other')");
        ThreadLocalUtil.set(Map.of(JwtClaimsConstant.USER_ID, 1L, JwtClaimsConstant.ROLE, "ROLE_USER"));
    }

    @AfterEach
    void clear() {
        ThreadLocalUtil.remove();
    }

    private Long insertPlaylist(String coverUrl, boolean custom) {
        jdbc.update("INSERT INTO tb_playlist(title, cover_url, cover_is_custom, is_public, status, owner_user_id) VALUES (?, ?, ?, TRUE, 'PUBLISHED', 1)",
                "Mine", coverUrl, custom);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM tb_playlist", Long.class);
        return id;
    }

    private int taskCount() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM tb_media_cleanup_task", Integer.class);
        return n == null ? 0 : n;
    }

    private String taskKey() {
        return jdbc.queryForObject("SELECT object_key FROM tb_media_cleanup_task", String.class);
    }

    @Test
    @DisplayName("替换自定义封面：旧对象入队（新对象不入队），且不就地删文件")
    void queuesPreviousCustomCoverOnReplace() {
        Long id = insertPlaylist(OLD_COVER, true);
        when(minio.uploadFile(any(), anyString())).thenReturn(NEW_COVER);

        personal.updateCover(id, image("new.png"));

        assertEquals(1, taskCount());
        assertEquals("playlists/old-cover.jpg", taskKey());
        assertEquals(NEW_COVER, playlists.lockById(id).getCoverUrl());
        verify(minio, never()).deleteFile(anyString());
        verify(minio, never()).deleteObject(anyString());
    }

    @Test
    @DisplayName("删除自定义封面的歌单：封面对象入队，且不就地删文件")
    void queuesCustomCoverOnPlaylistDelete() {
        Long id = insertPlaylist(OLD_COVER, true);

        personal.delete(id);

        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM tb_playlist", Long.class));
        assertEquals(1, taskCount());
        assertEquals("playlists/old-cover.jpg", taskKey());
        verify(minio, never()).deleteObject(anyString());
    }

    @Test
    @DisplayName("删除使用默认封面的歌单：不入队（默认封面是歌曲的共享图）")
    void neverQueuesDerivedDefaultCover() {
        Long id = insertPlaylist(OLD_COVER, false);

        personal.delete(id);

        assertEquals(0, taskCount());
        verify(minio, never()).deleteObject(anyString());
    }

    private static MockMultipartFile image(String name) {
        return new MockMultipartFile("cover", name, "image/png", new byte[]{1, 2, 3});
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        private static final JdbcDataSource SOURCE = new JdbcDataSource();

        Config() {
            SOURCE.setURL("jdbc:h2:mem:playlist_cover_cleanup;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        }

        @Bean
        DataSource dataSource() {
            return SOURCE;
        }

        @Bean
        DataSourceTransactionManager transactionManager() {
            return new DataSourceTransactionManager(SOURCE);
        }

        @Bean
        SqlSessionTemplate session() throws Exception {
            MybatisConfiguration config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            config.addMapper(PlaylistMapper.class);
            config.addMapper(PlaylistBindingMapper.class);
            config.addMapper(PlaylistCreateRequestMapper.class);
            config.addMapper(SongMapper.class);
            config.addMapper(MediaCleanupTaskMapper.class);
            config.addMapper(MediaReferenceMapper.class);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(SOURCE);
            factory.setConfiguration(config);
            factory.setMapperLocations(new ClassPathResource("mapper/PlaylistMapper.xml"));
            MybatisPlusInterceptor pagination = new MybatisPlusInterceptor();
            pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.H2));
            factory.setPlugins(pagination);
            return new SqlSessionTemplate(factory.getObject());
        }

        @Bean
        PlaylistMapper playlists(SqlSessionTemplate session) {
            return session.getMapper(PlaylistMapper.class);
        }

        @Bean
        PlaylistBindingMapper bindings(SqlSessionTemplate session) {
            return session.getMapper(PlaylistBindingMapper.class);
        }

        @Bean
        PlaylistCreateRequestMapper receipts(SqlSessionTemplate session) {
            return session.getMapper(PlaylistCreateRequestMapper.class);
        }

        @Bean
        SongMapper songs(SqlSessionTemplate session) {
            return session.getMapper(SongMapper.class);
        }

        @Bean
        MediaCleanupTaskMapper taskMapper(SqlSessionTemplate session) {
            return session.getMapper(MediaCleanupTaskMapper.class);
        }

        @Bean
        MediaReferenceMapper referenceMapper(SqlSessionTemplate session) {
            return session.getMapper(MediaReferenceMapper.class);
        }

        @Bean
        PlaylistAccessService access(PlaylistMapper mapper) {
            return new PlaylistAccessService(mapper);
        }

        @Bean
        MinioService minio() {
            return mock(MinioService.class);
        }

        @Bean
        MediaReferenceService mediaReferenceService(MediaReferenceMapper mapper) {
            return new MediaReferenceService(mapper);
        }

        @Bean
        MediaCleanupService mediaCleanup(MediaCleanupTaskMapper taskMapper, MediaReferenceService referenceService,
                                         MinioService minio) {
            return new MediaCleanupService(taskMapper, referenceService, minio, "vibe-music-data");
        }

        @Bean
        IPlaylistService details() {
            IPlaylistService service = mock(IPlaylistService.class);
            when(service.getPlaylistDetail(any(), any())).thenReturn(Result.success(new PlaylistDetailVO()));
            return service;
        }

        @Bean
        ObjectMapper json() {
            return new ObjectMapper();
        }

        @Bean
        LocalValidatorFactoryBean validator() {
            return new LocalValidatorFactoryBean();
        }

        @Bean
        PersonalPlaylistService personal(PlaylistMapper mapper, PlaylistBindingMapper bindings,
                                         PlaylistCreateRequestMapper receipts, SongMapper songs,
                                         PlaylistAccessService access, IPlaylistService details,
                                         ObjectMapper json, Validator validator, MinioService minio,
                                         MediaCleanupService mediaCleanup) {
            return new PersonalPlaylistService(mapper, bindings, receipts, songs, access, details, json, validator,
                    minio, mediaCleanup);
        }
    }
}
