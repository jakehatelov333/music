package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.mapper.*;
import cn.edu.seig.vibemusic.model.entity.Song;
import cn.edu.seig.vibemusic.model.dto.CommentPlaylistDTO;
import cn.edu.seig.vibemusic.service.impl.UserFavoriteServiceImpl;
import cn.edu.seig.vibemusic.service.impl.CommentServiceImpl;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FavoriteIdempotencyTest {
    private JdbcTemplate jdbc;
    private UserFavoriteServiceImpl service;
    private UserFavoriteMapper favorites;
    private SongMapper songs;
    private PlaylistAccessService access;

    @BeforeEach void setup() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:favorite_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE tb_user_favorite(id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, type INT NOT NULL, song_id BIGINT, playlist_id BIGINT, create_time TIMESTAMP NOT NULL, UNIQUE(user_id,type,song_id), UNIQUE(user_id,type,playlist_id))");
        var configuration = new MybatisConfiguration();
        configuration.addMapper(UserFavoriteMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source); factory.setConfiguration(configuration);
        favorites = new SqlSessionTemplate(factory.getObject()).getMapper(UserFavoriteMapper.class);
        songs = mock(SongMapper.class); access = mock(PlaylistAccessService.class);
        when(songs.selectById(10L)).thenReturn(new Song());
        service = new UserFavoriteServiceImpl();
        ReflectionTestUtils.setField(service, "userFavoriteMapper", favorites);
        ReflectionTestUtils.setField(service, "songMapper", songs);
        ReflectionTestUtils.setField(service, "playlistAccessService", access);
        login(1L);
    }
    @AfterEach void cleanup() { ThreadLocalUtil.remove(); }

    @Test void repeatingFavoritesAndCancellationAlwaysSucceed() {
        assertEquals(0, service.collectSong(10L).getCode());
        assertEquals(0, service.collectSong(10L).getCode());
        assertEquals(0, service.collectPlaylist(20L).getCode());
        assertEquals(0, service.collectPlaylist(20L).getCode());
        assertEquals(2, count());
        assertEquals(0, service.cancelCollectSong(10L).getCode());
        assertEquals(0, service.cancelCollectSong(10L).getCode());
        assertEquals(0, service.cancelCollectPlaylist(20L).getCode());
        assertEquals(0, service.cancelCollectPlaylist(20L).getCode());
        assertEquals(0, count());
    }

    @Test void concurrentRequestsUseDatabaseConstraintsAndKeepUsersSeparate() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> jobs = new ArrayList<>();
        try {
            for (int i = 0; i < 24; i++) {
                long userId = i % 2 + 1;
                jobs.add(pool.submit(() -> {
                    try {
                        start.await(); login(userId);
                        service.collectSong(10L); service.collectPlaylist(20L);
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                    finally { ThreadLocalUtil.remove(); }
                }));
            }
            start.countDown();
            for (var job : jobs) job.get(15, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertEquals(4, count());
        login(1L); service.cancelCollectSong(10L);
        assertEquals(List.of(10L), favorites.getUserFavoriteSongIds(2L));
    }

    @Test void unknownInvalidAndUnauthenticatedFavoritesNeverWrite() {
        assertStatus(404, () -> service.collectSong(999L));
        assertStatus(400, () -> service.collectSong(-1L));
        ThreadLocalUtil.remove();
        assertStatus(401, () -> service.collectSong(10L));
        assertEquals(0, count());
    }

    @Test void unreadablePlaylistsCannotBeFavoritedOrCommentedOn() {
        when(access.lockReadable(20L)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        assertStatus(404, () -> service.collectPlaylist(20L));
        var comments = new CommentServiceImpl();
        var mapper = mock(CommentMapper.class);
        ReflectionTestUtils.setField(comments, "playlistAccessService", access);
        ReflectionTestUtils.setField(comments, "commentMapper", mapper);
        var input = new CommentPlaylistDTO(); input.setPlaylistId(20L); input.setContent("hello");
        assertStatus(404, () -> comments.addPlaylistComment(input));
        verifyNoInteractions(mapper);
        assertEquals(0, count());
    }

    private int count() { return jdbc.queryForObject("SELECT COUNT(*) FROM tb_user_favorite", Integer.class); }
    private static void login(long id) { ThreadLocalUtil.set(Map.of(JwtClaimsConstant.USER_ID,id,JwtClaimsConstant.ROLE,"ROLE_USER")); }
    private static void assertStatus(int code, Runnable action) {
        assertEquals(code, assertThrows(ResponseStatusException.class, action::run).getStatusCode().value());
    }
}
