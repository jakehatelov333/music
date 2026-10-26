package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.enumeration.LikeStatusEnum;
import cn.edu.seig.vibemusic.mapper.AlbumMapper;
import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.mapper.UserFavoriteMapper;
import cn.edu.seig.vibemusic.model.vo.ArtistDetailVO;
import cn.edu.seig.vibemusic.model.vo.SongVO;
import cn.edu.seig.vibemusic.service.SongArtistService;
import cn.edu.seig.vibemusic.service.artist.ArtistDetailReadService;
import cn.edu.seig.vibemusic.service.impl.ArtistServiceImpl;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtistDetailCacheIsolationTest {

    private static final long ARTIST_ID = 88L;
    private static final long SONG_ID = 501L;

    @AfterEach
    void clearIdentity() {
        ThreadLocalUtil.remove();
    }

    @Test
    @DisplayName("歌手详情缓存只存公共读模型：A 的收藏状态不污染 B 与游客")
    void favoriteStatusIsAppliedAfterCacheAndNeverShared() {
        ArtistMapper artistMapper = mock(ArtistMapper.class);
        AlbumMapper albumMapper = mock(AlbumMapper.class);
        SongArtistService songArtists = mock(SongArtistService.class);
        UserFavoriteMapper favorites = mock(UserFavoriteMapper.class);

        when(artistMapper.getArtistDetailById(ARTIST_ID)).thenAnswer(invocation -> {
            ArtistDetailVO detail = new ArtistDetailVO();
            detail.setArtistId(ARTIST_ID);
            detail.setArtistName("测试歌手");
            SongVO song = new SongVO();
            song.setSongId(SONG_ID);
            song.setSongName("测试歌曲");
            song.setLikeStatus(LikeStatusEnum.LIKE.getId());
            detail.setSongs(new ArrayList<>(List.of(song)));
            return detail;
        });
        when(albumMapper.getAlbumsByArtistId(ARTIST_ID)).thenReturn(new ArrayList<>());

        when(favorites.getFavoriteSongIdsByUserId(anyLong()))
                .thenReturn(List.of(SONG_ID), List.of());

        ArtistDetailReadService readService = new ArtistDetailReadService(
                artistMapper, albumMapper, songArtists);
        ArtistDetailReadService cachedReads = withCaching(readService);

        ArtistServiceImpl service = new ArtistServiceImpl();
        ReflectionTestUtils.setField(service, "artistDetailReadService", cachedReads);
        ReflectionTestUtils.setField(service, "userFavoriteMapper", favorites);

        ThreadLocalUtil.set(Map.of(
                JwtClaimsConstant.USER_ID, 1L,
                JwtClaimsConstant.ROLE, "ROLE_USER"));
        ArtistDetailVO first = service.getArtistDetail(ARTIST_ID, new MockHttpServletRequest()).getData();
        assertEquals(LikeStatusEnum.LIKE.getId(), first.getSongs().get(0).getLikeStatus());

        ThreadLocalUtil.set(Map.of(
                JwtClaimsConstant.USER_ID, 2L,
                JwtClaimsConstant.ROLE, "ROLE_USER"));
        ArtistDetailVO second = service.getArtistDetail(ARTIST_ID, new MockHttpServletRequest()).getData();
        assertEquals(LikeStatusEnum.DEFAULT.getId(), second.getSongs().get(0).getLikeStatus());

        ThreadLocalUtil.remove();
        ArtistDetailVO anonymous = service.getArtistDetail(ARTIST_ID, new MockHttpServletRequest()).getData();
        assertEquals(LikeStatusEnum.DEFAULT.getId(), anonymous.getSongs().get(0).getLikeStatus());

        verify(artistMapper, times(1)).getArtistDetailById(ARTIST_ID);
    }

    @SuppressWarnings("unchecked")
    private static <T> T withCaching(T target) {
        CacheInterceptor cache = new CacheInterceptor();
        cache.setCacheManager(new ConcurrentMapCacheManager("artistCache"));
        cache.setCacheOperationSources(new AnnotationCacheOperationSource());
        cache.afterPropertiesSet();
        cache.afterSingletonsInstantiated();
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(cache);
        return (T) proxy.getProxy();
    }
}
