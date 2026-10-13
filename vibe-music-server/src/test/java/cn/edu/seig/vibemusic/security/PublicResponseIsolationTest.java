package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.mapper.SongMapper;
import cn.edu.seig.vibemusic.mapper.PlaylistMapper;
import cn.edu.seig.vibemusic.mapper.UserFavoriteMapper;
import cn.edu.seig.vibemusic.model.entity.UserFavorite;
import cn.edu.seig.vibemusic.model.vo.SongDetailVO;
import cn.edu.seig.vibemusic.model.vo.PlaylistDetailVO;
import cn.edu.seig.vibemusic.service.SongArtistService;
import cn.edu.seig.vibemusic.service.impl.SongServiceImpl;
import cn.edu.seig.vibemusic.service.impl.PlaylistServiceImpl;
import cn.edu.seig.vibemusic.util.JwtUtil;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.cache.annotation.AnnotationCacheOperationSource;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PublicResponseIsolationTest {
    @AfterEach void clearIdentity() { ThreadLocalUtil.remove(); }
    @SuppressWarnings("unchecked") private static <T> T withCaching(T target) {
        CacheInterceptor cache = new CacheInterceptor();
        cache.setCacheManager(new ConcurrentMapCacheManager("songCache", "playlistCache"));
        cache.setCacheOperationSources(new AnnotationCacheOperationSource());
        cache.afterPropertiesSet();
        cache.afterSingletonsInstantiated();
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(cache);
        return (T) proxy.getProxy();
    }

    private static MockHttpServletRequest loggedIn() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", JwtUtil.generateToken(Map.of(
                JwtClaimsConstant.USER_ID, 1L, JwtClaimsConstant.ROLE, "ROLE_USER")));
        return request;
    }

    @Test void songDetailsDoNotReuseAnotherUsersFavoriteStatus() {
        SongMapper songs = mock(SongMapper.class);
        UserFavoriteMapper favorites = mock(UserFavoriteMapper.class);
        when(songs.getSongDetailById(8L)).thenAnswer(call -> {
            SongDetailVO detail = new SongDetailVO(); detail.setSongId(8L); detail.setLikeStatus(0); return detail;
        });
        when(favorites.selectOne(any())).thenReturn(new UserFavorite());
        SongServiceImpl target = new SongServiceImpl();
        ReflectionTestUtils.setField(target, "songMapper", songs);
        ReflectionTestUtils.setField(target, "userFavoriteMapper", favorites);
        // 本用例只关心缓存隔离，歌手关系用替身即可（读路径装配 artists 不影响 likeStatus）。
        ReflectionTestUtils.setField(target, "songArtistService", mock(SongArtistService.class));
        SongServiceImpl service = withCaching(target);
        assertEquals(1, service.getSongDetail(8L, loggedIn()).getData().getLikeStatus());
        assertEquals(0, service.getSongDetail(8L, new MockHttpServletRequest()).getData().getLikeStatus());
    }

    @Test void playlistDetailsDoNotReuseAnotherUsersFavoriteStatus() {
        PlaylistMapper playlists = mock(PlaylistMapper.class);
        UserFavoriteMapper favorites = mock(UserFavoriteMapper.class);
        when(playlists.getPlaylistDetailById(eq(8L), nullable(Long.class))).thenAnswer(call -> {
            PlaylistDetailVO detail = new PlaylistDetailVO(); detail.setPlaylistId(8L);
            detail.setSongs(new ArrayList<>()); return detail;
        });
        when(favorites.selectOne(any())).thenReturn(new UserFavorite());
        when(favorites.getFavoriteSongIdsByUserId(1L)).thenReturn(List.of());
        PlaylistServiceImpl target = new PlaylistServiceImpl();
        ReflectionTestUtils.setField(target, "playlistMapper", playlists);
        ReflectionTestUtils.setField(target, "userFavoriteMapper", favorites);
        ReflectionTestUtils.setField(target, "songArtistService", mock(SongArtistService.class));
        PlaylistServiceImpl service = withCaching(target);
        ThreadLocalUtil.set(Map.of(JwtClaimsConstant.USER_ID, 1L, JwtClaimsConstant.ROLE, "ROLE_USER"));
        assertEquals(1, service.getPlaylistDetail(8L, loggedIn()).getData().getLikeStatus());
        ThreadLocalUtil.remove();
        assertEquals(0, service.getPlaylistDetail(8L, new MockHttpServletRequest()).getData().getLikeStatus());
    }
}
