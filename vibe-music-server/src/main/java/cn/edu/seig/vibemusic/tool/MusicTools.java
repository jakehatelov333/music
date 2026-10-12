package cn.edu.seig.vibemusic.tool;

import cn.edu.seig.vibemusic.enumeration.PlaylistStatusEnum;
import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.mapper.PlaylistMapper;
import cn.edu.seig.vibemusic.model.agent.*;
import cn.edu.seig.vibemusic.model.entity.Artist;
import cn.edu.seig.vibemusic.model.entity.Playlist;
import cn.edu.seig.vibemusic.service.agent.AgentSongQueryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

/**
 * Agent 只读工具集（歌曲 / 歌手 / 歌单查询）。
 *
 * <p><b>返回值协议：</b>每个工具都返回 {@link ToolResult} 的 JSON 文本。
 * 之所以返回 String 而不是让框架去序列化对象：LangChain4j 0.36.2 在
 * {@code DefaultToolExecutor} 里对非 String 返回值会走自己的 {@code Json.toJson}，
 * 那是一条不可控的序列化路径；返回已序列化的 JSON 可以完全掌握字段、
 * 并让“成功但为空”与“调用失败”的语义在模型侧清晰可辨。
 * 有专门的单元测试断言这个 JSON 能被 Jackson 正常反序列化。
 *
 * <p><b>用户上下文：</b>工具方法签名里<b>没有任何用户标识参数</b>。
 * 身份由 {@link AgentContext} 在服务端注入，模型无法伪造。
 *
 * <p><b>不可变性：</b>{@link #withContext} 返回新实例而不是改字段，
 * 保证同一个 Spring 单例 bean 被并发请求复用时不会串上下文。
 */
@Slf4j
@Component
public class MusicTools {

    private final AgentSongQueryService songQuery;
    private final ArtistMapper artistMapper;
    private final PlaylistMapper playlistMapper;
    private final AgentContext context;

    @Autowired
    public MusicTools(AgentSongQueryService songQuery, ArtistMapper artistMapper, PlaylistMapper playlistMapper) {
        this(songQuery, artistMapper, playlistMapper, AgentContext.none());
    }

    public MusicTools(AgentSongQueryService songQuery, ArtistMapper artistMapper, PlaylistMapper playlistMapper,
                      AgentContext context) {
        this.songQuery = songQuery;
        this.artistMapper = artistMapper;
        this.playlistMapper = playlistMapper;
        this.context = context == null ? AgentContext.none() : context;
    }

    /** 绑定本次运行的可信上下文，返回新实例；单例 bean 本身始终是无上下文的。 */
    public MusicTools withContext(AgentContext value) {
        return new MusicTools(songQuery, artistMapper, playlistMapper, value);
    }

    @Tool("搜索歌曲。支持按歌名、歌手名、专辑名、风格的模糊关键词查找，"
            + "返回带 songId、歌手、专辑、封面和播放地址的结构化歌曲列表。"
            + "当用户想找歌、想按条件挑歌、或需要拿到歌曲 ID 以便后续创建歌单时使用。"
            + "参数 keyword 为关键词；返回结果里没有歌曲表示没有命中，不代表调用失败。")
    public String searchSongs(String keyword) {
        return invoke("searchSongs", "正在搜索歌曲…", () -> {
            if (keyword == null || keyword.isBlank()) return ToolResult.invalid("请提供搜索关键词");
            List<SongRef> songs = songQuery.searchByKeyword(keyword, 10);
            return songs.isEmpty()
                    ? ToolResult.empty(AgentToolKinds.SONG_LIST, "未找到与「" + keyword + "」相关的歌曲，可以换个关键词试试")
                    : ToolResult.ok(AgentToolKinds.SONG_LIST, "找到 " + songs.size() + " 首与「" + keyword + "」相关的歌曲",
                            songs, songs.size());
        });
    }

    @Tool("按歌手名精确搜索该歌手演唱的歌曲，返回结构化歌曲列表（含 songId 与播放地址）。"
            + "当用户指定了歌手、想听某位歌手的歌时使用。参数 artistName 为歌手名。")
    public String searchSongsByArtist(String artistName) {
        return invoke("searchSongsByArtist", "正在搜索该歌手的歌曲…", () -> {
            if (artistName == null || artistName.isBlank()) return ToolResult.invalid("请提供歌手名");
            List<SongRef> songs = songQuery.search(null, artistName, null, null, 20);
            return songs.isEmpty()
                    ? ToolResult.empty(AgentToolKinds.SONG_LIST, "未找到歌手「" + artistName + "」的歌曲")
                    : ToolResult.ok(AgentToolKinds.SONG_LIST,
                            "找到歌手「" + artistName + "」的 " + songs.size() + " 首歌曲", songs, songs.size());
        });
    }

    @Tool("按音乐风格查找歌曲，返回结构化歌曲列表。参数 styleName 为风格名，例如「流行」「摇滚」「民谣」。")
    public String getSongsByStyle(String styleName) {
        return invoke("getSongsByStyle", "正在按风格查找歌曲…", () -> {
            if (styleName == null || styleName.isBlank()) return ToolResult.invalid("请提供音乐风格");
            List<SongRef> songs = songQuery.search(null, null, null, styleName, 20);
            return songs.isEmpty()
                    ? ToolResult.empty(AgentToolKinds.SONG_LIST, "未找到风格为「" + styleName + "」的歌曲")
                    : ToolResult.ok(AgentToolKinds.SONG_LIST,
                            "找到 " + songs.size() + " 首「" + styleName + "」风格的歌曲", songs, songs.size());
        });
    }

    @Tool("随机获取推荐歌曲，用于音乐发现。用户说「随便来点歌」「推荐一些歌」且没有明确条件时使用。")
    public String getRandomSongs() {
        return invoke("getRandomSongs", "正在挑选歌曲…", () -> {
            List<SongRef> songs = songQuery.random(10);
            return songs.isEmpty()
                    ? ToolResult.empty(AgentToolKinds.SONG_LIST, "曲库中暂无歌曲数据")
                    : ToolResult.ok(AgentToolKinds.SONG_LIST, "为你随机挑选了 " + songs.size() + " 首歌", songs, songs.size());
        });
    }

    @Tool("搜索歌手，按歌手名或地区模糊匹配，返回歌手列表。当用户想了解某位歌手时先调用本工具确认歌手是否存在。")
    public String searchArtists(String keyword) {
        return invoke("searchArtists", "正在搜索歌手…", () -> {
            if (keyword == null || keyword.isBlank()) return ToolResult.invalid("请提供搜索关键词");
            LambdaQueryWrapper<Artist> wrapper = new LambdaQueryWrapper<>();
            wrapper.like(Artist::getArtistName, keyword).or().like(Artist::getArea, keyword).last("LIMIT 10");
            List<ArtistRef> artists = artistMapper.selectList(wrapper).stream()
                    .map(MusicTools::toArtistRef).toList();
            return artists.isEmpty()
                    ? ToolResult.empty(AgentToolKinds.ARTIST_LIST, "未找到与「" + keyword + "」相关的歌手")
                    : ToolResult.ok(AgentToolKinds.ARTIST_LIST, "找到 " + artists.size() + " 位相关歌手",
                            artists, artists.size());
        });
    }

    @Tool("获取指定歌手的详细信息（性别、地区、出生日期、简介）。参数 artistName 必须与曲库中的歌手名一致。")
    public String getArtistDetail(String artistName) {
        return invoke("getArtistDetail", "正在查询歌手资料…", () -> {
            if (artistName == null || artistName.isBlank()) return ToolResult.invalid("请提供歌手名");
            LambdaQueryWrapper<Artist> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Artist::getArtistName, artistName).last("LIMIT 1");
            List<Artist> found = artistMapper.selectList(wrapper);
            if (found.isEmpty()) {
                return ToolResult.rejected(AgentToolCodes.NOT_FOUND, "曲库中没有名为「" + artistName + "」的歌手");
            }
            ArtistRef ref = toArtistRef(found.get(0));
            return ToolResult.ok(AgentToolKinds.ARTIST_DETAIL, "已获取歌手「" + artistName + "」的资料", ref, 1);
        });
    }

    @Tool("搜索歌单，按歌单标题或风格模糊匹配，只返回已发布且公开的歌单。参数 keyword 为关键词。")
    public String searchPlaylists(String keyword) {
        return invoke("searchPlaylists", "正在搜索歌单…", () -> {
            if (keyword == null || keyword.isBlank()) return ToolResult.invalid("请提供搜索关键词");
            LambdaQueryWrapper<Playlist> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Playlist::getIsPublic, true)
                    // 草稿绝不能通过 Agent 回答泄漏出去。
                    .and(q -> q.isNull(Playlist::getStatus)
                            .or().eq(Playlist::getStatus, PlaylistStatusEnum.PUBLISHED.getStatus()))
                    .and(q -> q.like(Playlist::getTitle, keyword).or().like(Playlist::getStyle, keyword))
                    .last("LIMIT 10");
            List<PlaylistRef> playlists = playlistMapper.selectList(wrapper).stream()
                    .map(MusicTools::toPlaylistRef).toList();
            return playlists.isEmpty()
                    ? ToolResult.empty(AgentToolKinds.PLAYLIST_LIST, "未找到与「" + keyword + "」相关的歌单")
                    : ToolResult.ok(AgentToolKinds.PLAYLIST_LIST, "找到 " + playlists.size() + " 个相关歌单",
                            playlists, playlists.size());
        });
    }

    /**
     * 统一的工具执行外壳：计时、事件上报、异常兜底、指标计数。
     * 任何未捕获异常都会被转成 {@code ok=false, code=ERROR}，模型据此可以换策略而不是崩溃。
     */
    private String invoke(String tool, String label, Supplier<ToolResult<?>> body) {
        String toolCallId = context.nextToolCallId(tool);
        context.emit(AgentEvent.toolStart(context.taskId(), toolCallId, tool, label));
        long start = System.nanoTime();
        ToolResult<?> result;
        try {
            result = body.get();
        } catch (RuntimeException e) {
            log.warn("工具执行异常 tool={} type={}", tool, e.getClass().getSimpleName());
            result = ToolResult.error("工具「" + tool + "」执行失败，请稍后重试或换一种方式提问");
        }
        long durationMs = (System.nanoTime() - start) / 1_000_000L;

        if (context.metrics() != null) {
            context.metrics().toolCalled();
            if (!result.ok()) context.metrics().toolFailed();
            if (AgentToolKinds.SONG_LIST.equals(result.kind())) context.metrics().resultsFound(result.total());
        }
        context.emit(AgentEvent.toolEnd(context.taskId(), toolCallId, tool, result, durationMs));
        if (result.ok() && result.data() != null && AgentToolKinds.SONG_LIST.equals(result.kind())) {
            context.emit(AgentEvent.songs(context.taskId(), toolCallId, result));
        }
        return json(result);
    }

    private static ArtistRef toArtistRef(Artist artist) {
        return new ArtistRef(artist.getArtistId(), artist.getArtistName(), artist.getArea(),
                artist.getGender() == null ? null : (artist.getGender() == 0 ? "男" : "女"),
                artist.getBirth() == null ? null : artist.getBirth().toString(),
                artist.getIntroduction());
    }

    private static PlaylistRef toPlaylistRef(Playlist playlist) {
        return new PlaylistRef(playlist.getPlaylistId(), playlist.getTitle(), playlist.getStyle(),
                playlist.getIntroduction(), playlist.getCoverUrl());
    }

    static String json(Object value) {
        return AgentJson.write(value);
    }
}
