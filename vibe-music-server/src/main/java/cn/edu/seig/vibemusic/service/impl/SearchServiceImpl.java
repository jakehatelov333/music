package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.enumeration.LikeStatusEnum;
import cn.edu.seig.vibemusic.enumeration.RoleEnum;
import cn.edu.seig.vibemusic.mapper.SearchMapper;
import cn.edu.seig.vibemusic.mapper.UserFavoriteMapper;
import cn.edu.seig.vibemusic.model.entity.UserFavorite;
import cn.edu.seig.vibemusic.model.vo.ArtistVO;
import cn.edu.seig.vibemusic.model.vo.AlbumVO;
import cn.edu.seig.vibemusic.model.vo.GlobalSearchVO;
import cn.edu.seig.vibemusic.model.vo.PlaylistVO;
import cn.edu.seig.vibemusic.model.vo.SongVO;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.ISearchService;
import cn.edu.seig.vibemusic.service.SongArtistService;
import cn.edu.seig.vibemusic.util.JwtUtil;
import cn.edu.seig.vibemusic.util.TypeConversionUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 全局音乐搜索服务实现。
 */
@Service
public class SearchServiceImpl implements ISearchService {

    private static final int MAX_LIMIT = 50;

    @Autowired
    private SearchMapper searchMapper;

    @Autowired
    private UserFavoriteMapper userFavoriteMapper;

    @Autowired
    private SongArtistService songArtistService;

    @Override
    public Result<GlobalSearchVO> search(String keyword, int limit, HttpServletRequest request) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        if (normalizedKeyword.isEmpty()) {
            return Result.success(emptyResult());
        }

        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<SongVO> songs = searchMapper.searchSongs(normalizedKeyword, safeLimit);
        List<ArtistVO> artists = searchMapper.searchArtists(normalizedKeyword, safeLimit);
        List<AlbumVO> albums = searchMapper.searchAlbums(normalizedKeyword, safeLimit);
        List<PlaylistVO> playlists = searchMapper.searchPlaylists(normalizedKeyword, safeLimit);

        setSongLikeStatus(songs, request);
        // 结构化歌手列表：独立批量查询，主搜索 SQL 保持零 JOIN。
        songArtistService.attachArtists(songs);

        GlobalSearchVO result = new GlobalSearchVO();
        result.setSongs(songs);
        result.setArtists(artists);
        result.setAlbums(albums);
        result.setPlaylists(playlists);
        return Result.success(result);
    }

    private void setSongLikeStatus(List<SongVO> songs, HttpServletRequest request) {
        songs.forEach(song -> song.setLikeStatus(LikeStatusEnum.DEFAULT.getId()));

        Long userId = getUserId(request);
        if (userId == null || songs.isEmpty()) {
            return;
        }

        List<UserFavorite> favoriteSongs = userFavoriteMapper.selectList(new QueryWrapper<UserFavorite>()
                .eq("user_id", userId)
                .eq("type", 0));
        Set<Long> favoriteSongIds = favoriteSongs.stream()
                .map(UserFavorite::getSongId)
                .collect(Collectors.toSet());

        songs.forEach(song -> {
            if (favoriteSongIds.contains(song.getSongId())) {
                song.setLikeStatus(LikeStatusEnum.LIKE.getId());
            }
        });
    }

    private Long getUserId(HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        if (token == null || token.isEmpty()) {
            return null;
        }
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        if (token.isEmpty()) {
            return null;
        }

        try {
            Map<String, Object> claims = JwtUtil.parseToken(token);
            String role = (String) claims.get(JwtClaimsConstant.ROLE);
            if (!RoleEnum.USER.getRole().equals(role)) {
                return null;
            }
            return TypeConversionUtil.toLong(claims.get(JwtClaimsConstant.USER_ID));
        } catch (Exception ignored) {
            return null;
        }
    }

    private GlobalSearchVO emptyResult() {
        GlobalSearchVO result = new GlobalSearchVO();
        result.setSongs(Collections.emptyList());
        result.setArtists(Collections.emptyList());
        result.setAlbums(Collections.emptyList());
        result.setPlaylists(Collections.emptyList());
        return result;
    }
}
