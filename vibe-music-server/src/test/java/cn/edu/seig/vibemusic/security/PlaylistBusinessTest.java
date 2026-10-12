package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.mapper.*;
import cn.edu.seig.vibemusic.model.dto.*;
import cn.edu.seig.vibemusic.model.entity.Playlist;
import cn.edu.seig.vibemusic.service.IPlaylistService;
import cn.edu.seig.vibemusic.service.MinioService;
import cn.edu.seig.vibemusic.service.PersonalPlaylistService;
import cn.edu.seig.vibemusic.service.impl.PlaylistServiceImpl;
import cn.edu.seig.vibemusic.tool.MusicTools;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import cn.edu.seig.vibemusic.util.JwtUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.annotation.DbType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated MySQL-mode database: exercises real mapper SQL, locks, unique constraints and rollback. */
@SpringJUnitConfig(PlaylistBusinessTest.Config.class)
class PlaylistBusinessTest {
    @Autowired PersonalPlaylistService personal;
    @Autowired IPlaylistService details;
    @Autowired PlaylistMapper playlists;
    @Autowired SearchMapper search;
    @Autowired MusicTools musicTools;
    @Autowired DataSource dataSource;
    @Autowired MinioService minio;
    private JdbcTemplate jdbc;

    @BeforeEach void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_user(id BIGINT PRIMARY KEY, username VARCHAR(255), user_avatar VARCHAR(255))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_artist(id BIGINT PRIMARY KEY, name VARCHAR(255))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_song(id BIGINT PRIMARY KEY, name VARCHAR(255), album VARCHAR(255), duration VARCHAR(20), cover_url VARCHAR(255), audio_url VARCHAR(255), release_time DATE, artist_id BIGINT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_playlist(id BIGINT AUTO_INCREMENT PRIMARY KEY, title VARCHAR(255), cover_url VARCHAR(255), introduction VARCHAR(2000), style VARCHAR(255), owner_user_id BIGINT, is_public BOOLEAN DEFAULT TRUE, status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED', cover_is_custom BOOLEAN NOT NULL DEFAULT FALSE, FOREIGN KEY(owner_user_id) REFERENCES tb_user(id) ON DELETE CASCADE)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_playlist_binding(playlist_id BIGINT, song_id BIGINT, PRIMARY KEY(playlist_id,song_id), FOREIGN KEY(playlist_id) REFERENCES tb_playlist(id) ON DELETE CASCADE, FOREIGN KEY(song_id) REFERENCES tb_song(id) ON DELETE CASCADE)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_comment(id BIGINT PRIMARY KEY, playlist_id BIGINT, type INT, content VARCHAR(255), create_time TIMESTAMP, like_count BIGINT, user_id BIGINT, FOREIGN KEY(playlist_id) REFERENCES tb_playlist(id) ON DELETE CASCADE)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_user_favorite(id BIGINT PRIMARY KEY, user_id BIGINT, playlist_id BIGINT, type INT, create_time TIMESTAMP, FOREIGN KEY(playlist_id) REFERENCES tb_playlist(id) ON DELETE CASCADE)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_playlist_create_request(user_id BIGINT, request_key VARCHAR(64), request_hash CHAR(64), playlist_id BIGINT, PRIMARY KEY(user_id,request_key), FOREIGN KEY(user_id) REFERENCES tb_user(id) ON DELETE CASCADE)");
        // Test-only in-memory rows. No external database is configured in this context.
        jdbc.update("DELETE FROM tb_playlist_create_request");
        jdbc.update("DELETE FROM tb_playlist");
        jdbc.update("DELETE FROM tb_song");
        jdbc.update("DELETE FROM tb_user");
        jdbc.update("INSERT INTO tb_user(id) VALUES (1), (2)");
        jdbc.update("INSERT INTO tb_song(id,name) VALUES (10,'Song A'), (11,'Song B')");
        user(1L);
    }
    @AfterEach void clear() { ThreadLocalUtil.remove(); }

    @Test void creationIsPrivateAndDuplicateSongsAndRetriesAreIdempotent() {
        var first = personal.create("create-request-0001", create("Mine", List.of(10L, 10L, 11L)));
        var again = personal.create("create-request-0001", create(" Mine ", List.of(11L, 10L)));
        assertEquals(first.getPlaylistId(), again.getPlaylistId());
        assertFalse(first.getIsPublic());
        assertTrue(first.getEditable());
        assertEquals(1L, first.getOwnerUserId());
        assertEquals(2, first.getSongs().size());
        assertEquals(1, count("tb_playlist"));
        assertEquals(2, count("tb_playlist_binding"));
    }

    @Test void reusedKeyWithDifferentBodyConflictsWithoutMutation() {
        personal.create("create-request-0002", create("First", List.of(10L)));
        assertStatus(409, () -> personal.create("create-request-0002", create("Second", List.of(11L))));
        assertEquals(1, count("tb_playlist"));
        assertEquals(1, count("tb_playlist_binding"));
    }

    @Test void rawAuthorizationHeaderDoesNotGrantPrivateAccessWithoutVerifiedIdentity() {
        Long id = personal.create("create-request-auth", create("Private", List.of())).getPlaylistId();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + JwtUtil.generateToken(Map.of(
                JwtClaimsConstant.USER_ID, 1L, JwtClaimsConstant.ROLE, "ROLE_USER")));
        ThreadLocalUtil.remove();
        assertStatus(404, () -> details.getPlaylistDetail(id, request));
    }

    @Test void validationAlsoAppliesWhenCallingBusinessServiceDirectly() {
        assertStatus(400, () -> personal.create(null, create("No key", List.of())));
        assertThrows(jakarta.validation.ConstraintViolationException.class,
                () -> personal.create("create-request-invalid", create(" ", List.of())));
        assertEquals(0, count("tb_playlist_create_request"));
    }

    @Test void invalidSongRollsBackWholeCreationIncludingReceipt() {
        assertStatus(404, () -> personal.create("create-request-0003", create("Bad", List.of(10L, 999L))));
        assertEquals(0, count("tb_playlist"));
        assertEquals(0, count("tb_playlist_create_request"));
        personal.create("create-request-0003", create("Fixed", List.of(10L)));
        assertEquals(1, count("tb_playlist"));
    }

    @Test void bindingFailureRollsBackInsertedPlaylistAndReceipt() {
        // Enforce a temporary test-only DB constraint to fail after metadata was inserted.
        jdbc.execute("ALTER TABLE tb_playlist_binding ADD CONSTRAINT test_reject_song CHECK(song_id <> 11)");
        try {
            assertThrows(RuntimeException.class, () -> personal.create("create-request-0004", create("Rollback", List.of(10L,11L))));
            assertEquals(0, count("tb_playlist"));
            assertEquals(0, count("tb_playlist_binding"));
            assertEquals(0, count("tb_playlist_create_request"));
        } finally {
            jdbc.execute("ALTER TABLE tb_playlist_binding DROP CONSTRAINT test_reject_song");
        }
    }

    @Test void everyPersonalMutationRejectsOtherOwnersAndPlatformPlaylists() {
        Long id = personal.create("create-request-0005", create("Private", List.of(10L))).getPlaylistId();
        user(2L);
        assertStatus(404, () -> details.getPlaylistDetail(id, null));
        assertStatus(404, () -> personal.update(id, create("Stolen", List.of())));
        assertStatus(404, () -> personal.addSongs(id, songs(11L)));
        assertStatus(404, () -> personal.removeSong(id, 10L));
        assertStatus(404, () -> personal.delete(id));
        Playlist platform = new Playlist().setTitle("Platform").setIsPublic(true);
        playlists.insert(platform);
        assertStatus(404, () -> personal.addSongs(platform.getPlaylistId(), songs(11L)));
        assertStatus(403, () -> details.deletePlaylist(id));
        admin();
        // 管理员可以删除用户歌单做内容治理，但改元数据仍只对平台歌单开放。
        assertStatus(404, () -> details.updatePlaylist(update(id, "Hijacked")));
        details.deletePlaylist(id);
        assertEquals(0, count("tb_playlist_binding"));
        ThreadLocalUtil.remove();
        assertStatus(401, () -> personal.create("create-request-guest", create("Guest", List.of())));
    }

    @Test void publicDiscoverySearchToolsAndFavoritesHidePrivatePlaylists() {
        Long id = personal.create("create-request-0006", create("Unique private", List.of(10L))).getPlaylistId();
        Playlist publicOne = new Playlist().setTitle("Unique public").setStyle("Unique").setIsPublic(true);
        playlists.insert(publicOne);
        assertEquals(1, details.getAllPlaylists(query()).getData().getTotal());
        assertEquals(1, playlists.getRandomPlaylists(10).size());
        assertEquals(1, search.searchPlaylists("Unique", 10).size());
        assertFalse(musicTools.searchPlaylists("Unique").contains("private"));
        user(2L);
        assertEquals(0, playlists.getPlaylistsByIds(2L, new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1,10),
                List.of(id), null, null).getTotal());
        assertEquals(0, personal.mine(query()).getTotal());
        user(1L);
        assertEquals(1, personal.mine(query()).getTotal());
        var changed = create("Unique private", List.of()); changed.setIsPublic(true);
        personal.update(id, changed);
        ThreadLocalUtil.remove();
        assertNotNull(details.getPlaylistDetail(id, null).getData());
        user(1L); changed.setIsPublic(false); personal.update(id, changed);
        user(2L);
        assertStatus(404, () -> details.getPlaylistDetail(id, null));
    }

    @Test void repeatedAddRemoveAndDeleteKeepStableStateAndDeletedReplayDoesNotRecreate() {
        Long id = personal.create("create-request-0007", create("Lifecycle", List.of(10L))).getPlaylistId();
        personal.addSongs(id, songs(10L,11L,11L)); personal.addSongs(id, songs(11L));
        assertEquals(2, count("tb_playlist_binding"));
        assertStatus(404, () -> personal.addSongs(id, songs(10L,999L)));
        assertEquals(2, count("tb_playlist_binding"));
        personal.removeSong(id,10L); personal.removeSong(id,10L);
        assertEquals(1, count("tb_playlist_binding"));
        jdbc.update("INSERT INTO tb_comment(id,playlist_id,type) VALUES (1,?,1)", id);
        jdbc.update("INSERT INTO tb_user_favorite(id,user_id,playlist_id,type) VALUES (1,2,?,1)", id);
        personal.delete(id); personal.delete(id);
        assertEquals(0, count("tb_playlist_binding"));
        assertEquals(0, count("tb_comment"));
        assertEquals(0, count("tb_user_favorite"));
        assertStatus(410, () -> personal.create("create-request-0007", create("Lifecycle", List.of(10L))));
        assertEquals(0, count("tb_playlist"));
    }

    @Test void concurrentCreationAndAddSongsAreSerializedWithoutDuplicates() throws Exception {
        List<Long> created = concurrent(6, () -> personal.create("create-request-parallel", create("Concurrent", List.of(10L))).getPlaylistId());
        assertEquals(1, created.stream().distinct().count());
        Long id = created.get(0);
        concurrent(6, () -> { personal.addSongs(id, songs(10L,11L)); return id; });
        assertEquals(1, count("tb_playlist"));
        assertEquals(2, count("tb_playlist_binding"));
    }

    @Test void draftsStayHiddenUntilPublishedAndPublishingAlwaysMakesPlatformPlaylistsPublic() {
        admin();
        var created = details.addPlaylist(platform("Draft only", List.of(10L))).getData();
        Long id = created.getPlaylistId();
        assertEquals("DRAFT", created.getStatus());
        assertTrue(created.getIsPublic());
        // A draft is invisible even though it is marked public.
        ThreadLocalUtil.remove();
        assertStatus(404, () -> details.getPlaylistDetail(id, null));
        assertEquals(0, details.getAllPlaylists(query()).getData().getTotal());
        assertEquals(0, playlists.getRandomPlaylists(10).size());
        assertEquals(0, search.searchPlaylists("Draft only", 10).size());
        assertTrue(musicTools.searchPlaylists("Draft only").contains("未找到"));

        admin();
        details.publishPlaylist(id);
        ThreadLocalUtil.remove();
        assertNotNull(details.getPlaylistDetail(id, null).getData());
        assertEquals(1, details.getAllPlaylists(query()).getData().getTotal());

        // Platform playlists never carry private visibility; the draft state is the only way back.
        admin();
        details.updatePlaylist(update(id, "Draft only"));
        assertTrue(playlists.lockById(id).getIsPublic());
        details.unpublishPlaylist(id);
        assertEquals("DRAFT", playlists.lockById(id).getStatus());
        ThreadLocalUtil.remove();
        assertStatus(404, () -> details.getPlaylistDetail(id, null));
    }

    @Test void adminListCoversUserPlaylistsAndFiltersBySource() {
        admin();
        Long platformId = details.addPlaylist(platform("Platform only", List.of())).getData().getPlaylistId();
        user(1L);
        Long userId = personal.create("create-request-admin-list", create("User owned", List.of())).getPlaylistId();

        admin();
        assertEquals(2, details.getAllPlaylistsInfo(adminQuery(null)).getData().getTotal());
        var platformOnly = details.getAllPlaylistsInfo(adminQuery("PLATFORM")).getData();
        assertEquals(1, platformOnly.getTotal());
        assertEquals(platformId, platformOnly.getItems().get(0).getPlaylistId());
        assertNull(platformOnly.getItems().get(0).getOwnerUserId());

        var userOnly = details.getAllPlaylistsInfo(adminQuery("USER")).getData();
        assertEquals(1, userOnly.getTotal());
        assertEquals(userId, userOnly.getItems().get(0).getPlaylistId());
        assertEquals(1L, userOnly.getItems().get(0).getOwnerUserId());

        assertStatus(400, () -> details.getAllPlaylistsInfo(adminQuery("NOPE")));

        // 管理员可以查看用户歌单详情，但元数据编辑仍只对平台歌单开放。
        assertEquals(userId, details.getAdminPlaylistDetail(userId).getData().getPlaylistId());
        assertStatus(404, () -> details.updatePlaylist(update(userId, "hijacked")));
        assertStatus(404, () -> details.publishPlaylist(userId));

        // 删除对用户歌单开放，用于内容治理，并走既有的级联删除。
        details.deletePlaylist(userId);
        assertEquals(0, count("tb_playlist_binding"));
        assertEquals(1, count("tb_playlist"));
    }

    @Test void publicListPutsOwnPlaylistsLastAndMarksFavorites() {
        admin();
        Long platformId = details.addPlaylist(platform("Platform A", List.of())).getData().getPlaylistId();
        details.publishPlaylist(platformId);
        user(1L);
        Long mine = personal.create("create-request-ordering", create("Mine public", List.of())).getPlaylistId();
        var published = create("Mine public", List.of());
        published.setIsPublic(true);
        personal.update(mine, published);

        jdbc.update("INSERT INTO tb_user_favorite(id,user_id,playlist_id,type,create_time) VALUES (1,1,?,1,CURRENT_TIMESTAMP)", platformId);

        user(1L);
        var page = details.getAllPlaylists(query()).getData();
        assertEquals(2, page.getTotal());
        var items = page.getItems();
        // 自己创建的歌单排到最后，别人的排在前面
        assertEquals(platformId, items.get(0).getPlaylistId());
        assertEquals(mine, items.get(1).getPlaylistId());
        assertTrue(items.get(0).getFavorite());
        assertFalse(items.get(0).getOwned());
        assertTrue(items.get(1).getOwned());
        assertFalse(items.get(1).getFavorite());

        // 未登录时按 id 倒序，且不做任何个性化标记
        ThreadLocalUtil.remove();
        var anonymous = details.getAllPlaylists(query()).getData();
        assertEquals(2, anonymous.getTotal());
        assertEquals(mine, anonymous.getItems().get(0).getPlaylistId());
        assertFalse(anonymous.getItems().get(0).getOwned());
        assertFalse(anonymous.getItems().get(0).getFavorite());
    }

    @Test void defaultCoverFollowsFirstSongAndSurvivesSongAdditionsUntilOverridden() {
        jdbc.update("INSERT INTO tb_song(id,name,cover_url) VALUES (20,'Covered A','http://minio/a.jpg'), (21,'Covered B','http://minio/b.jpg'), (22,'No cover',NULL)");
        var created = personal.create("create-request-cover", create("Covered", List.of(22L, 21L)));
        // Song 22 has no cover, so the first song with a usable cover wins.
        assertEquals("http://minio/b.jpg", created.getCoverUrl());
        assertFalse(created.getCoverIsCustom());

        var grown = personal.addSongs(created.getPlaylistId(), songs(20L));
        // The stored default cover is never replaced once set.
        assertEquals("http://minio/b.jpg", grown.getCoverUrl());
        assertFalse(grown.getCoverIsCustom());
    }

    @Test void customCoverIsPersistedAndNeverOverwrittenBySongAdditions() {
        jdbc.update("INSERT INTO tb_song(id,name,cover_url) VALUES (20,'Covered A','http://minio/a.jpg')");
        when(minio.uploadFile(any(), anyString())).thenReturn("http://minio/uploaded.jpg");
        Long id = personal.create("create-request-custom", create("Custom", List.of(20L))).getPlaylistId();

        var uploaded = personal.updateCover(id, image("cover.png", "image/png", new byte[]{1, 2, 3}));
        assertEquals("http://minio/uploaded.jpg", uploaded.getCoverUrl());
        assertTrue(uploaded.getCoverIsCustom());

        jdbc.update("INSERT INTO tb_song(id,name,cover_url) VALUES (21,'Covered B','http://minio/b.jpg')");
        var grown = personal.addSongs(id, songs(21L));
        assertEquals("http://minio/uploaded.jpg", grown.getCoverUrl());
        assertTrue(grown.getCoverIsCustom());

        // Restoring the automatic cover clears the custom flag and re-derives from the first song.
        var restored = personal.resetCover(id);
        assertEquals("http://minio/a.jpg", restored.getCoverUrl());
        assertFalse(restored.getCoverIsCustom());
    }

    @Test void coverUploadValidatesFormatAndSize() {
        Long id = personal.create("create-request-bad-cover", create("Bad cover", List.of(10L))).getPlaylistId();
        assertStatus(400, () -> personal.updateCover(id, image("empty.png", "image/png", new byte[0])));
        assertStatus(400, () -> personal.updateCover(id, image("notes.txt", "text/plain", new byte[]{1})));
        assertStatus(400, () -> personal.updateCover(id,
                image("huge.png", "image/png", new byte[(int) (5 * 1024 * 1024) + 1])));
        assertStatus(400, () -> personal.updateCover(id, null));
        assertNull(playlists.lockById(id).getCoverUrl());
        assertFalse(playlists.lockById(id).getCoverIsCustom());
    }

    private static MockMultipartFile image(String name, String contentType, byte[] content) {
        return new MockMultipartFile("cover", name, contentType, content);
    }

    private static PlaylistAddDTO platform(String title, List<Long> songIds) {
        var dto = new PlaylistAddDTO();
        dto.setTitle(title); dto.setSongIds(songIds);
        return dto;
    }

    private static PlaylistUpdateDTO update(Long id, String title) {
        var dto = new PlaylistUpdateDTO();
        dto.setPlaylistId(id); dto.setTitle(title);
        return dto;
    }

    private static AdminPlaylistQueryDTO adminQuery(String source) {
        var dto = new AdminPlaylistQueryDTO();
        dto.setPageNum(1); dto.setPageSize(50); dto.setSource(source);
        return dto;
    }

    private <T> List<T> concurrent(int workers, Callable<T> work) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i=0;i<workers;i++) futures.add(executor.submit(() -> {
                user(1L); start.await();
                try { return work.call(); } finally { ThreadLocalUtil.remove(); }
            }));
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(15, TimeUnit.SECONDS));
            return results;
        } finally { executor.shutdownNow(); }
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private static void user(Long id) { ThreadLocalUtil.set(Map.of(JwtClaimsConstant.USER_ID,id,JwtClaimsConstant.ROLE,"ROLE_USER")); }
    private static void admin() { ThreadLocalUtil.set(Map.of(JwtClaimsConstant.ADMIN_ID,1L,JwtClaimsConstant.ROLE,"ROLE_ADMIN")); }
    private static PersonalPlaylistCreateDTO create(String title,List<Long> ids) {
        var dto = new PersonalPlaylistCreateDTO(); dto.setTitle(title); dto.setSongIds(ids); return dto;
    }
    private static PlaylistSongsDTO songs(Long... ids) { var dto = new PlaylistSongsDTO();dto.setSongIds(List.of(ids));return dto; }
    private static PlaylistDTO query() { var dto = new PlaylistDTO();dto.setPageNum(1);dto.setPageSize(20);return dto; }
    private static void assertStatus(int expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(ResponseStatusException.class, action).getStatusCode().value());
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:playlist_business;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
            return source;
        }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean SqlSessionTemplate session(DataSource source) throws Exception {
            MybatisConfiguration config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
            config.addMapper(PlaylistMapper.class); config.addMapper(PlaylistBindingMapper.class);
            config.addMapper(PlaylistCreateRequestMapper.class); config.addMapper(SongMapper.class); config.addMapper(SearchMapper.class);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
            factory.setMapperLocations(new ClassPathResource("mapper/PlaylistMapper.xml"));
            MybatisPlusInterceptor pagination = new MybatisPlusInterceptor(); pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.H2));
            factory.setPlugins(pagination);
            return new SqlSessionTemplate(factory.getObject());
        }
        @Bean PlaylistMapper playlistMapper(SqlSessionTemplate session) { return session.getMapper(PlaylistMapper.class); }
        @Bean PlaylistBindingMapper bindings(SqlSessionTemplate session) { return session.getMapper(PlaylistBindingMapper.class); }
        @Bean PlaylistCreateRequestMapper receipts(SqlSessionTemplate session) { return session.getMapper(PlaylistCreateRequestMapper.class); }
        @Bean SongMapper songs(SqlSessionTemplate session) { return session.getMapper(SongMapper.class); }
        @Bean SearchMapper search(SqlSessionTemplate session) { return session.getMapper(SearchMapper.class); }
        @Bean PlaylistAccessService access(PlaylistMapper mapper) { return new PlaylistAccessService(mapper); }
        @Bean IPlaylistService details() { return new PlaylistServiceImpl(); }
        @Bean UserFavoriteMapper favorites() { return mock(UserFavoriteMapper.class); }
        @Bean MinioService minio() { return mock(MinioService.class); }
        @Bean ObjectMapper json() { return new ObjectMapper(); }
        @Bean LocalValidatorFactoryBean validator() { return new LocalValidatorFactoryBean(); }
        @Bean PersonalPlaylistService personal(PlaylistMapper mapper, PlaylistBindingMapper bindings,
                PlaylistCreateRequestMapper receipts, SongMapper songs, PlaylistAccessService access,
                IPlaylistService details, ObjectMapper json, Validator validator, MinioService minio) {
            return new PersonalPlaylistService(mapper,bindings,receipts,songs,access,details,json,validator,minio);
        }
        @Bean MusicTools tools(PlaylistMapper playlists) {
            return new MusicTools(mock(cn.edu.seig.vibemusic.service.agent.AgentSongQueryService.class),
                    mock(ArtistMapper.class), playlists);
        }
    }
}
