package cn.edu.seig.vibemusic.tool;

import cn.edu.seig.vibemusic.enumeration.PlaylistStatusEnum;
import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.mapper.PlaylistMapper;
import cn.edu.seig.vibemusic.mapper.SongMapper;
import cn.edu.seig.vibemusic.model.entity.Artist;
import cn.edu.seig.vibemusic.model.entity.Playlist;
import cn.edu.seig.vibemusic.model.entity.Song;
import cn.edu.seig.vibemusic.model.vo.SongVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class MusicTools {

    private final SongMapper songMapper;
    private final ArtistMapper artistMapper;
    private final PlaylistMapper playlistMapper;

    @Tool("搜索歌曲，根据关键词匹配歌名或专辑名，返回匹配的歌曲列表")
    public String searchSongs(String keyword) {
        log.debug("Tool调用: searchSongs");
        LambdaQueryWrapper<Song> wrapper = new LambdaQueryWrapper<>();
        wrapper
                .like(Song::getSongName, keyword)
                .or()
                .like(Song::getAlbum, keyword)
                .last("LIMIT 10");
        List<Song> songs = songMapper.selectList(wrapper);
        if (songs.isEmpty()) {
            return "未找到与 " + keyword + " 相关的歌曲";
        }
        return songs.stream()
                .map(s -> String.format("歌曲: %s | 专辑: %s | 风格: %s | 时长: %s",
                        s.getSongName(), s.getAlbum(), s.getStyle(), s.getDuration()))
                .collect(Collectors.joining("\n"));
    }

    @Tool("搜索歌手，根据关键词匹配歌手名称或地区，返回匹配的歌手列表")
    public String searchArtists(String keyword) {
        log.debug("Tool调用: searchArtists");
        LambdaQueryWrapper<Artist> wrapper = new LambdaQueryWrapper<>();
        wrapper
                .like(Artist::getArtistName, keyword)
                .or()
                .like(Artist::getArea, keyword)
                .last("LIMIT 10");
        List<Artist> artists = artistMapper.selectList(wrapper);
        if (artists.isEmpty()) {
            return "未找到与 " + keyword + " 相关的歌手";
        }
        return artists.stream()
                .map(a -> String.format("歌手: %s | 地区: %s | 简介: %s",
                        a.getArtistName(), a.getArea(),
                        a.getIntroduction() != null ? a.getIntroduction() : "暂无"))
                .collect(Collectors.joining("\n"));
    }

    @Tool("获取指定音乐风格的歌曲列表")
    public String getSongsByStyle(String styleName) {
        log.debug("Tool调用: getSongsByStyle");
        LambdaQueryWrapper<Song> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Song::getStyle, styleName).last("LIMIT 10");
        List<Song> songs = songMapper.selectList(wrapper);
        if (songs.isEmpty()) {
            return "未找到风格为 " + styleName + " 的歌曲";
        }
        return songs.stream()
                .map(s -> String.format("歌曲: %s | 专辑: %s | 风格: %s | 时长: %s",
                        s.getSongName(), s.getAlbum(), s.getStyle(), s.getDuration()))
                .collect(Collectors.joining("\n"));
    }

    @Tool("随机获取推荐的歌曲列表，用于音乐发现和推荐")
    public String getRandomSongs() {
        log.info("Tool调用: getRandomSongs");
        List<SongVO> songs = songMapper.getRandomSongsWithArtist();
        if (songs.isEmpty()) {
            return "暂无歌曲数据";
        }
        return songs.stream()
                .map(s -> String.format("歌曲: %s | 歌手: %s | 专辑: %s | 时长: %s",
                        s.getSongName(), s.getArtistName(), s.getAlbum(), s.getDuration()))
                .collect(Collectors.joining("\n"));
    }

    @Tool("获取指定歌手的详细信息，包括简介、地区、出生日期等")
    public String getArtistDetail(String artistName) {
        log.debug("Tool调用: getArtistDetail");
        LambdaQueryWrapper<Artist> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Artist::getArtistName, artistName);
        List<Artist> artists = artistMapper.selectList(wrapper);
        if (artists.isEmpty()) {
            return "未找到歌手: " + artistName;
        }
        Artist a = artists.get(0);
        return String.format("歌手: %s | 性别: %s | 地区: %s | 出生: %s | 简介: %s",
                a.getArtistName(),
                a.getGender() != null ? (a.getGender() == 0 ? "男" : "女") : "未知",
                a.getArea() != null ? a.getArea() : "未知",
                a.getBirth() != null ? a.getBirth().toString() : "未知",
                a.getIntroduction() != null ? a.getIntroduction() : "暂无");
    }

    @Tool("根据歌手名称搜索该歌手的歌曲，传入歌手名字，返回该歌手演唱的歌曲列表")
    public String searchSongsByArtist(String artistName) {
        log.debug("Tool调用: searchSongsByArtist");
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<SongVO> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10);
        var result = songMapper.getSongsWithArtist(page, null, artistName, null);
        List<SongVO> songs = result.getRecords();
        if (songs.isEmpty()) {
            return "未找到歌手 " + artistName + " 的歌曲";
        }
        return songs.stream()
                .map(s -> String.format("歌曲: %s | 歌手: %s | 专辑: %s | 时长: %s",
                        s.getSongName(), s.getArtistName(), s.getAlbum(), s.getDuration()))
                .collect(Collectors.joining("\n"));
    }

    @Tool("搜索歌单，根据关键词匹配歌单标题或风格，返回匹配的歌单列表")
    public String searchPlaylists(String keyword) {
        log.debug("Tool调用: searchPlaylists");
        LambdaQueryWrapper<Playlist> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Playlist::getIsPublic, true)
                // Drafts must never surface through Agent answers either.
                .and(q -> q.isNull(Playlist::getStatus)
                        .or().eq(Playlist::getStatus, PlaylistStatusEnum.PUBLISHED.getStatus()))
                .and(q -> q.like(Playlist::getTitle, keyword).or().like(Playlist::getStyle, keyword))
                .last("LIMIT 10");
        List<Playlist> playlists = playlistMapper.selectList(wrapper);
        if (playlists.isEmpty()) {
            return "未找到与 " + keyword + " 相关的歌单";
        }
        return playlists.stream()
                .map(p -> String.format("歌单: %s | 风格: %s | 简介: %s",
                        p.getTitle(), p.getStyle(),
                        p.getIntroduction() != null ? p.getIntroduction() : "暂无"))
                .collect(Collectors.joining("\n"));
    }
}
