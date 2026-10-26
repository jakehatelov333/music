package cn.edu.seig.vibemusic.service.artist;

import cn.edu.seig.vibemusic.enumeration.LikeStatusEnum;
import cn.edu.seig.vibemusic.mapper.AlbumMapper;
import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.model.vo.ArtistDetailVO;
import cn.edu.seig.vibemusic.model.vo.SongVO;
import cn.edu.seig.vibemusic.service.SongArtistService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Anonymous-safe artist detail read model.
 *
 * <p>This bean never reads the authenticated principal. The cached object is shared by
 * every viewer, so per-user favorite state must be applied by the caller to a copy.
 */
@Service
@RequiredArgsConstructor
public class ArtistDetailReadService {

    private final ArtistMapper artistMapper;
    private final AlbumMapper albumMapper;
    private final SongArtistService songArtistService;

    @Cacheable(cacheNames = "artistCache", key = "#artistId + '-albums-v5'")
    public ArtistDetailVO load(Long artistId) {
        ArtistDetailVO detail = artistMapper.getArtistDetailById(artistId);
        if (detail == null) {
            return null;
        }
        detail.setAlbums(albumMapper.getAlbumsByArtistId(artistId));
        List<SongVO> songs = detail.getSongs();
        if (songs != null) {
            songs.forEach(song -> song.setLikeStatus(LikeStatusEnum.DEFAULT.getId()));
            songArtistService.attachArtists(songs);
            detail.setSongs(songs);
        }
        return detail;
    }
}
