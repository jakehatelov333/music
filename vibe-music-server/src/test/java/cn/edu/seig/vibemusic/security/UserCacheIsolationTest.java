package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.mapper.*;
import cn.edu.seig.vibemusic.model.dto.SongDTO;
import cn.edu.seig.vibemusic.model.dto.PlaylistDTO;
import cn.edu.seig.vibemusic.model.vo.SongVO;
import cn.edu.seig.vibemusic.service.IUserFavoriteService;
import cn.edu.seig.vibemusic.service.SongArtistService;
import cn.edu.seig.vibemusic.service.impl.UserFavoriteServiceImpl;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.*;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserCacheIsolationTest {
    @AfterEach void cleanup() { ThreadLocalUtil.remove(); }

    @Configuration @EnableCaching
    static class Config {
        @Bean CacheManager cacheManager() { return new ConcurrentMapCacheManager("userFavoriteCache"); }
        @Bean UserCacheKeys userCacheKeys() { return new UserCacheKeys(new ObjectMapper()); }
        @Bean UserFavoriteMapper favorites() { return mock(UserFavoriteMapper.class); }
        @Bean SongMapper songs() { return mock(SongMapper.class); }
        @Bean PlaylistMapper playlists() { return mock(PlaylistMapper.class); }
        @Bean PlaylistAccessService playlistAccess() { return mock(PlaylistAccessService.class); }
        // 歌手关系在本用例里无关，用替身即可（只验证缓存键按用户隔离）。
        @Bean SongArtistService songArtistService() { return mock(SongArtistService.class); }
        @Bean IUserFavoriteService service() { return new UserFavoriteServiceImpl(); }
    }

    @Test void sameQueryUsesSeparateCachesForTwoUsersAndStillCachesWithinOneUser() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Config.class)) {
            IUserFavoriteService service = context.getBean(IUserFavoriteService.class);
            UserFavoriteMapper favorites = context.getBean(UserFavoriteMapper.class);
            SongMapper songs = context.getBean(SongMapper.class);
            when(favorites.getUserFavoriteSongIds(1L)).thenReturn(List.of());
            when(favorites.getUserFavoriteSongIds(2L)).thenReturn(List.of(22L));
            SongVO song = new SongVO(); song.setSongId(22L);
            Page<SongVO> page = new Page<>(1, 10, 1); page.setRecords(List.of(song));
            when(songs.getSongsByIds(any(), anyLong(), eq(List.of(22L)), isNull(), isNull(), isNull())).thenReturn(page);
            SongDTO query = new SongDTO(); query.setPageNum(1); query.setPageSize(10);
            ThreadLocalUtil.set(Map.of(JwtClaimsConstant.USER_ID, 1L));
            assertEquals(0L, service.getUserFavoriteSongs(query).getData().getTotal());
            ThreadLocalUtil.set(Map.of(JwtClaimsConstant.USER_ID, 2L));
            assertEquals(1L, service.getUserFavoriteSongs(query).getData().getTotal());
            assertEquals(1L, service.getUserFavoriteSongs(query).getData().getTotal());
            ThreadLocalUtil.set(Map.of(JwtClaimsConstant.USER_ID, 1L));
            assertEquals(0L, service.getUserFavoriteSongs(query).getData().getTotal());
            verify(favorites, times(1)).getUserFavoriteSongIds(1L);
            verify(favorites, times(1)).getUserFavoriteSongIds(2L);
        }
    }

    @Test void playlistCachesAlsoRemainIsolated() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Config.class)) {
            IUserFavoriteService service = context.getBean(IUserFavoriteService.class);
            UserFavoriteMapper favorites = context.getBean(UserFavoriteMapper.class);
            when(favorites.getUserFavoritePlaylistIds(anyLong())).thenReturn(List.of());
            PlaylistDTO query = new PlaylistDTO(); query.setPageNum(1); query.setPageSize(10);
            ThreadLocalUtil.set(Map.of(JwtClaimsConstant.USER_ID, 1L));
            service.getUserFavoritePlaylists(query);
            ThreadLocalUtil.set(Map.of(JwtClaimsConstant.USER_ID, 2L));
            service.getUserFavoritePlaylists(query);
            service.getUserFavoritePlaylists(query);
            verify(favorites, times(1)).getUserFavoritePlaylistIds(1L);
            verify(favorites, times(2)).getUserFavoritePlaylistIds(2L);
        }
    }

    /**
     * 缓存命名空间必须带「值结构版本号」。
     *
     * <p>收藏歌曲列表缓存的是 {@link SongVO}。一旦 VO 增删字段（例如新增 {@code artists}
     * 供前端跳转歌手页），或查询结果集语义变化（例如修掉重复行），旧缓存仍会命中，
     * 前端就会拿到缺字段 / 错行的旧数据 —— 表现是歌手名退化成纯文本、点不动，或列表出现重复歌曲。
     * 递增命名空间版本号是最小代价的失效手段。本用例把这条约定钉死：
     * <b>每次做不兼容变更，都要同步更新这里的版本号断言。</b>
     */
    @Test void cacheKeyNamespaceCarriesValueStructureVersion() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Config.class)) {
            UserCacheKeys keys = context.getBean(UserCacheKeys.class);
            ThreadLocalUtil.set(Map.of(JwtClaimsConstant.USER_ID, 7L));
            SongDTO query = new SongDTO();
            query.setPageNum(1);
            query.setPageSize(10);
            String key = keys.key("songs", query);
            assertTrue(key.startsWith("v4:"),
                    "SongVO 结构 / 结果集语义变化后缓存命名空间版本号必须递增，实际键=" + key);
            assertTrue(key.contains(":songs:7:"),
                    "缓存键必须同时包含业务名与用户 id，实际键=" + key);
        }
    }
}
