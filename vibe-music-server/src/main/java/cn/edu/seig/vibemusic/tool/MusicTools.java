package cn.edu.seig.vibemusic.tool;

import cn.edu.seig.vibemusic.enumeration.ArtistStatusEnum;
import cn.edu.seig.vibemusic.enumeration.PlaylistStatusEnum;
import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.mapper.PlaylistMapper;
import cn.edu.seig.vibemusic.model.agent.*;
import cn.edu.seig.vibemusic.model.entity.Artist;
import cn.edu.seig.vibemusic.model.entity.Playlist;
import cn.edu.seig.vibemusic.service.agent.AgentSongQueryService;
import cn.edu.seig.vibemusic.service.agent.PlaylistSelectionHistoryService;
import cn.edu.seig.vibemusic.service.agent.UserMemoryPreferenceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
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
 *
 * <p><b>两类搜索语义不同：</b>普通搜索（{@code searchSongs} / {@code searchSongsByArtist} /
 * {@code getSongsByStyle}）面向「用户想找歌」，是<b>用户当前明确发起的请求</b>，
 * 不受任何长期偏好或历史影响 —— 用户这次就是要找某位被排除的歌手/风格，也必须搜得到
 * （「当前请求优先于长期偏好」）。{@code searchPlaylistCandidates} 与 {@code getRandomSongs}
 * 面向「服务端替用户挑歌」，才会应用<b>确定性排除</b>：本会话近期歌单已用过的歌曲
 * （候选检索）与用户长期偏好里的排除歌手/排除风格（两者都应用）。
 * 两者不能互相替代，也不要给普通搜索加上排除逻辑。
 */
@Slf4j
@Component
public class MusicTools {

    /**
     * 推荐 / 找歌类工具的默认返回数量。
     *
     * <p><b>为什么是 5 而不是 10：</b>用户说「推荐几首」「来点类似的」，真正要的是一个能听完的短列表；
     * 10 首会让模型倾向于把整份结果复述一遍，界面上也是两页卡片。5 首刚好一屏，且符合「几首」的语感。
     * 用户明确说「多来几首」「全部」「20 首」时由模型自己把 limit 调大（上限 {@code MAX_RESULTS}）。
     *
     * <p><b>为什么不改 {@link #searchPlaylistCandidates}：</b>建歌单的候选集需要足够多以支撑挑选，
     * 沿用默认 10（见 {@link #DEFAULT_PLAYLIST_LIMIT}）—— 预览里的歌数由模型决定，不由默认值决定。
     */
    private static final int DEFAULT_RECOMMEND_LIMIT = 5;

    /** 歌单候选检索的默认数量：建歌单需要比「推荐几首」更大的候选池。 */
    private static final int DEFAULT_PLAYLIST_LIMIT = 10;

    private final AgentSongQueryService songQuery;
    private final ArtistMapper artistMapper;
    private final PlaylistMapper playlistMapper;
    private final PlaylistSelectionHistoryService selectionHistory;
    private final UserMemoryPreferenceService memoryPreferences;
    private final AgentContext context;

    @Autowired
    public MusicTools(AgentSongQueryService songQuery, ArtistMapper artistMapper, PlaylistMapper playlistMapper,
                      PlaylistSelectionHistoryService selectionHistory,
                      UserMemoryPreferenceService memoryPreferences) {
        this(songQuery, artistMapper, playlistMapper, selectionHistory, memoryPreferences, AgentContext.none());
    }

    public MusicTools(AgentSongQueryService songQuery, ArtistMapper artistMapper, PlaylistMapper playlistMapper,
                      PlaylistSelectionHistoryService selectionHistory,
                      UserMemoryPreferenceService memoryPreferences, AgentContext context) {
        this.songQuery = songQuery;
        this.artistMapper = artistMapper;
        this.playlistMapper = playlistMapper;
        this.selectionHistory = selectionHistory;
        this.memoryPreferences = memoryPreferences;
        this.context = context == null ? AgentContext.none() : context;
    }

    /** 绑定本次运行的可信上下文，返回新实例；单例 bean 本身始终是无上下文的。 */
    public MusicTools withContext(AgentContext value) {
        return new MusicTools(songQuery, artistMapper, playlistMapper, selectionHistory, memoryPreferences, value);
    }

    /**
     * 普通关键词搜索。<b>刻意不应用任何排除规则</b>（既不排除近期歌单用过的歌，也不排除用户偏好里的排除项）：
     * 这是「用户当前明确发起的请求」，优先级高于长期偏好与历史。用户这次主动搜被排除的歌手/风格，
     * 也必须能搜到 —— 排除规则只作用于「服务端替用户挑歌」的候选检索与随机推荐。
     */
    @Tool("搜索歌曲。支持按歌名、歌手名、专辑名、风格的模糊关键词查找，"
            + "返回带 songId、歌手、专辑、封面和播放地址的结构化歌曲列表。"
            + "当用户想找歌、想按条件挑歌、或需要拿到歌曲 ID 以便后续创建歌单时使用。"
            + "参数 keyword 为关键词；limit 留空默认 5，最大 20。"
            + "返回结果里没有歌曲表示没有命中，不代表调用失败。")
    public String searchSongs(String keyword,
                              @P(value = "期望返回的歌曲数量，留空默认 5，最大 20", required = false) String limit) {
        return invoke("searchSongs", "正在搜索歌曲…", () -> {
            if (keyword == null || keyword.isBlank()) return ToolResult.invalid("请提供搜索关键词");
            List<SongRef> songs = songQuery.searchByKeyword(keyword, parseLimit(limit, DEFAULT_RECOMMEND_LIMIT));
            return songs.isEmpty()
                    ? ToolResult.empty(AgentToolKinds.SONG_LIST, "未找到与「" + keyword + "」相关的歌曲，可以换个关键词试试")
                    : ToolResult.ok(AgentToolKinds.SONG_LIST, "找到 " + songs.size() + " 首与「" + keyword + "」相关的歌曲",
                            songs, songs.size()).withSearch(new SongSearch(keyword, null, null));
        });
    }

    /** 按歌手搜索。<b>不应用排除规则</b>：用户当前明确要听这位歌手时，长期偏好里的排除项不生效。 */
    @Tool("按歌手搜索该歌手演唱的歌曲（包含与其他歌手合作演唱的歌曲），"
            + "返回结构化歌曲列表（含 songId 与播放地址）。"
            + "当用户指定了歌手、想听某位歌手的歌时使用。参数 artistName 传单个歌手名即可，例如「银临」。"
            + "limit 留空默认 5，最大 20；用户明确要「多来几首」「全部」时才调大。"
            + "返回结果里没有歌曲表示这位歌手没有作品，不代表调用失败。")
    public String searchSongsByArtist(String artistName,
                                      @P(value = "期望返回的歌曲数量，留空默认 5，最大 20", required = false) String limit) {
        return invoke("searchSongsByArtist", "正在搜索该歌手的歌曲…", () -> {
            if (artistName == null || artistName.isBlank()) return ToolResult.invalid("请提供歌手名");
            List<SongRef> songs = songQuery.searchByArtistName(artistName, parseLimit(limit, DEFAULT_RECOMMEND_LIMIT));
            return songs.isEmpty()
                    ? ToolResult.empty(AgentToolKinds.SONG_LIST, "未找到歌手「" + artistName + "」的歌曲")
                    : ToolResult.ok(AgentToolKinds.SONG_LIST,
                            "歌手「" + artistName + "」的歌曲", songs, songs.size())
                            .withSearch(new SongSearch(null, artistName, null));
        });
    }

    /** 按风格查找。<b>不应用排除规则</b>：用户当前明确点某风格时，长期偏好里的排除项不生效。 */
    @Tool("按音乐风格查找歌曲，返回结构化歌曲列表。参数 styleName 为风格名，例如「流行」「摇滚」「民谣」。"
            + "limit 留空默认 5，最大 20；用户明确要「多来几首」「全部」时才调大。")
    public String getSongsByStyle(String styleName,
                                  @P(value = "期望返回的歌曲数量，留空默认 5，最大 20", required = false) String limit) {
        return invoke("getSongsByStyle", "正在按风格查找歌曲…", () -> {
            if (styleName == null || styleName.isBlank()) return ToolResult.invalid("请提供音乐风格");
            List<SongRef> songs = songQuery.search(null, null, null, styleName, parseLimit(limit, DEFAULT_RECOMMEND_LIMIT));
            return songs.isEmpty()
                    ? ToolResult.empty(AgentToolKinds.SONG_LIST, "未找到风格为「" + styleName + "」的歌曲")
                    : ToolResult.ok(AgentToolKinds.SONG_LIST,
                            "「" + styleName + "」风格的歌曲", songs, songs.size())
                            .withSearch(new SongSearch(null, null, styleName));
        });
    }

    /**
     * 歌单候选检索：与普通搜索最大的区别是<b>排除本会话近期歌单已经用过的歌曲</b>，
     * 以及<b>用户长期偏好里的排除歌手/排除风格</b>。
     *
     * <p>它是「连续创建同类歌单」不再重复选曲的关键一环：历史排除条件由服务端从
     * {@link PlaylistSelectionHistoryService} 取到（用户 + 会话双重隔离）；
     * 偏好排除由 {@link UserMemoryPreferenceService#exclusionConstraints(Long)} 取到
     * （内部复用同一套合并逻辑）。两者都交给 SQL 在 {@code LIMIT} 之前执行，
     * 因此不会因为原有的 {@code LIMIT} 而丢歌，也不会让「提示词里说了别推荐」停留在口头。
     *
     * <p>普通搜索工具（{@link #searchSongs}、{@link #searchSongsByArtist}、
     * {@link #getSongsByStyle}）刻意<b>不</b>做任何排除：用户只是想听某位歌手的歌时，
     * 不应该因为以前建过歌单、或曾经排除过该歌手就搜不到 ——
     * 「用户当前明确发起的请求」优先于「长期偏好」。
     */
    @Tool("为创建歌单检索候选歌曲。与普通搜索的区别：会排除当前用户在当前会话近期歌单预览中"
            + "已经用过的歌曲，也会排除用户长期偏好里明确排除的歌手与风格，"
            + "所以「再建一个同类歌单」时不会重复上一份歌单、也不会推荐用户不想听的歌。"
            + "artistName、keyword、style 至少填一个，不用的留空字符串；limit 可留空，默认 10，最大 20。"
            + "返回的 songId 都是数据库里真实存在的歌曲，可直接用于 preparePlaylistCreation。"
            + "如果返回数量少于期望，说明近期可用的新歌不足，应如实告诉用户实际能提供多少首，不要编造歌曲。")
    public String searchPlaylistCandidates(
            @P("歌手名，留空表示不限") String artistName,
            @P("关键词，匹配歌名/歌手/专辑/风格，留空表示不限") String keyword,
            @P("音乐风格，留空表示不限") String style,
            @P(value = "期望返回的歌曲数量，留空默认 10，最大 20", required = false) String limit) {
        return invoke("searchPlaylistCandidates", "正在挑选近期未用过的歌曲…", () -> {
            String artist = blankToNull(artistName);
            String word = blankToNull(keyword);
            String styleName = blankToNull(style);
            if (artist == null && word == null && styleName == null) {
                return ToolResult.invalid("请至少提供歌手、关键词或风格中的一个条件");
            }
            SongSearch criteria = new SongSearch(word, artist, styleName);
            // 历史严格按 (userId, chatId) 隔离；游客或缺少会话时得到空集合，等价于不过滤。
            Set<Long> used = selectionHistory.recentSongIds(context.userId(), context.chatId());
            // 长期偏好里的结构化排除（游客 / 关闭个性化 → 空约束）。
            UserMemoryPreferenceService.ExclusionConstraints exclusions =
                    memoryPreferences.exclusionConstraints(context.userId());
            List<SongRef> fresh = songQuery.searchExcluding(word, artist, styleName, used, exclusions,
                    parseLimit(limit));
            if (!fresh.isEmpty()) {
                return ToolResult.ok(AgentToolKinds.SONG_LIST,
                        "找到 " + fresh.size() + " 首近期歌单未使用过、且未命中排除条件的"
                                + describeScope(artist, word, styleName) + "歌曲",
                        fresh, fresh.size()).withSearch(criteria);
            }
            // 空结果时只给事实，不下指令：让模型自己判断是按歌手推荐、放宽条件、还是如实说明。
            return ToolResult.empty(AgentToolKinds.SONG_LIST,
                    emptyDiagnosis(artist, word, styleName, used, exclusions))
                    .withSearch(criteria);
        });
    }

    /**
     * 随机推荐：这是「无明确条件」的推荐路径，最容易把用户已排除的歌手/风格又推回来，
     * 因此必须应用与候选检索相同的结构化排除约束（SQL 层，{@code LIMIT} 之前）。
     */
    @Tool("随机获取推荐歌曲，用于音乐发现。用户说「随便来点歌」「推荐一些歌」且没有明确条件时使用。"
            + "会自动避开用户长期偏好里排除的歌手与风格。"
            + "limit 留空默认 5，最大 20；用户明确要「多来几首」时才调大。")
    public String getRandomSongs(
            @P(value = "期望返回的歌曲数量，留空默认 5，最大 20", required = false) String limit) {
        return invoke("getRandomSongs", "正在挑选歌曲…", () -> {
            UserMemoryPreferenceService.ExclusionConstraints exclusions =
                    memoryPreferences.exclusionConstraints(context.userId());
            List<SongRef> songs = songQuery.random(parseLimit(limit, DEFAULT_RECOMMEND_LIMIT), exclusions);
            return songs.isEmpty()
                    ? ToolResult.empty(AgentToolKinds.SONG_LIST, "曲库中暂无歌曲数据")
                    : ToolResult.ok(AgentToolKinds.SONG_LIST, "为你随机挑选了 " + songs.size() + " 首歌", songs, songs.size());
        });
    }

    /**
     * 最近播放明细。
     *
     * <p>之所以做成工具而不是塞进画像：画像里的播放信号只有「播过几首」「反复播放的歌手」，
     * 是<b>弱隐式偏好</b>；用户问「我最近听了什么」要的是<b>明细</b>（歌名/歌手/封面）。
     * 两者语义不同，混在一起会让画像变成一份变相的播放列表缓存。
     */
    @Tool("获取当前登录用户最近播放的歌曲（按最近一次有效播放时间倒序），"
            + "返回带 songId、歌名、歌手、专辑、封面和播放地址的结构化歌曲列表。"
            + "当用户问「我最近听了什么歌」「我的播放记录」「最近在循环哪几首」时使用。"
            + "limit 留空默认 10，最大 20。播放记录只对本人可见，工具不接受任何用户参数。")
    public String getRecentPlayedSongs(@P(value = "返回数量，留空默认 10，最大 20", required = false) String limit) {
        return invoke("getRecentPlayedSongs", "正在读取最近的播放记录…", () -> {
            if (!context.authenticated()) {
                return ToolResult.unauthorized("播放记录需要登录后查看，请先登录再问这个问题");
            }
            List<SongRef> songs = songQuery.recentlyPlayed(context.userId(), parseLimit(limit));
            return songs.isEmpty()
                    ? ToolResult.empty(AgentToolKinds.SONG_LIST,
                            "当前用户还没有播放记录。请如实告诉用户暂时没有记录（只有真正听够一定时长才会记一次），"
                                    + "不要猜歌名，也不要说系统不记录歌名。")
                    : ToolResult.ok(AgentToolKinds.SONG_LIST,
                            "用户最近播放过的 " + songs.size() + " 首歌（按最近播放时间倒序）",
                            songs, songs.size());
        });
    }

    @Tool("搜索歌手，按歌手名或地区模糊匹配，返回歌手列表。当用户想了解某位歌手时先调用本工具确认歌手是否存在。")
    public String searchArtists(String keyword) {
        return invoke("searchArtists", "正在搜索歌手…", () -> {
            if (keyword == null || keyword.isBlank()) return ToolResult.invalid("请提供搜索关键词");
            LambdaQueryWrapper<Artist> wrapper = new LambdaQueryWrapper<>();
            // 停用（软删除）的歌手不得出现在 Agent 的歌手检索里。
            // 注意 like 之间是 OR，必须用 and(...) 包起来，否则 OR 会吃掉前面的 status 条件。
            wrapper.eq(Artist::getStatus, ArtistStatusEnum.ACTIVE.getStatus())
                    .and(q -> q.like(Artist::getArtistName, keyword).or().like(Artist::getArea, keyword))
                    .last("LIMIT 10");
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
            wrapper.eq(Artist::getArtistName, artistName)
                    .eq(Artist::getStatus, ArtistStatusEnum.ACTIVE.getStatus())
                    .last("LIMIT 1");
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

    /** 返回数量：留空取调用方给的默认值，非法值同样回落，避免模型传错就整次调用失败。 */
    private static int parseLimit(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int value = Integer.parseInt(raw.trim());
            return Math.max(1, Math.min(value, AgentSongQueryService.MAX_RESULTS));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseLimit(String raw) {
        return parseLimit(raw, DEFAULT_PLAYLIST_LIMIT);
    }

    /** 把生效的筛选条件拼成一句人话，用于工具消息（模型据此向用户解释为什么没有新歌）。 */
    private static String describeScope(String artist, String keyword, String style) {
        List<String> parts = new java.util.ArrayList<>(3);
        if (artist != null) parts.add("歌手「" + artist + "」");
        if (keyword != null) parts.add("关键词「" + keyword + "」");
        if (style != null) parts.add("「" + style + "」风格");
        return parts.isEmpty() ? "" : String.join("、", parts);
    }

    /**
     * 候选检索为空时的<b>诊断事实</b>。
     *
     * <p><b>这里刻意只陈述事实，不下行动指令。</b>旧版返回「…可能曲库中没有…不要改用其它工具
     * 绕过排除条件」，其中「不要改用其它工具」是一条停止信号：模型拿到 EMPTY 后没有可走的路，
     * 只能把「曲库没有」转述给用户。真实事故里用户问「推荐最近播放歌曲类似风格的歌」，
     * 工具返回的正是这句，模型于是回答「很抱歉，曲库里暂时没有适合的推荐歌曲」——
     * 而实际上该歌手有 7 首，只是没有风格标注，两个条件取交集才为 0。
     *
     * <p>现在改为逐项给出<b>可观察的事实</b>：
     * <ul>
     *     <li>完整条件命中多少首（区分「曲库确实没有」与「条件太窄」）；</li>
     *     <li>分别放宽歌手 / 风格 / 关键词后各有多少首（模型可据此判断是哪一项卡住了）；</li>
     *     <li>其中有多少首被本会话历史或用户偏好排除（这是业务约束，必须让模型知道，
     *         但由它自己决定如何向用户表达）。</li>
     * </ul>
     *
     * <p><b>「用户排除条件不得绕过」这条业务约束仍然保留</b>，但表述为对<b>结果</b>的要求
     * （推荐里不能出现被排除的歌手/风格），而不是对<b>手段</b>的禁止 ——
     * 模型可以用任何工具探索，只是最终推荐必须满足约束。
     */
    private String emptyDiagnosis(String artist, String keyword, String style,
                                  Set<Long> used,
                                  UserMemoryPreferenceService.ExclusionConstraints exclusions) {
        StringBuilder sb = new StringBuilder();

        // ① 先把事实算全，再决定措辞。顺序很重要：曾在这里「exact==0 就提前返回」，
        //    结果恰恰吞掉了最需要说明的场景 —— 歌手有 7 首、风格有 57 首，但交集为 0
        //    （因为那 7 首没有风格标注）。那不是「曲库没有」，是「条件交集过窄」。
        long exact = songQuery.countMatching(keyword, artist, style);

        // ② 逐项放宽，指出是哪一项把结果压到了 0（单条件时无需放宽，自身就是答案）。
        List<String> relaxed = new java.util.ArrayList<>(2);
        long bestRelaxed = 0L;
        if (artist != null && style != null) {
            long byArtist = songQuery.countMatching(null, artist, null);
            long byStyle = songQuery.countMatching(null, null, style);
            relaxed.add("只按歌手「" + artist + "」有 " + byArtist + " 首");
            relaxed.add("只按风格「" + style + "」有 " + byStyle + " 首");
            bestRelaxed = Math.max(byArtist, byStyle);
        } else if (artist != null && keyword != null) {
            long byArtist = songQuery.countMatching(null, artist, null);
            long byKeyword = songQuery.countMatching(keyword, null, null);
            relaxed.add("只按歌手「" + artist + "」有 " + byArtist + " 首");
            relaxed.add("只按关键词「" + keyword + "」有 " + byKeyword + " 首");
            bestRelaxed = Math.max(byArtist, byKeyword);
        } else if (style != null && keyword != null) {
            long byStyle = songQuery.countMatching(null, null, style);
            long byKeyword = songQuery.countMatching(keyword, null, null);
            relaxed.add("只按风格「" + style + "」有 " + byStyle + " 首");
            relaxed.add("只按关键词「" + keyword + "」有 " + byKeyword + " 首");
            bestRelaxed = Math.max(byStyle, byKeyword);
        }

        // ③ 完整条件与放宽后都是 0：曲库里确实没有这类歌，这是「未找到」。
        if (exact == 0 && bestRelaxed == 0) {
            sb.append("未找到符合 ").append(describeScope(artist, keyword, style)).append(" 的歌曲。");
            sb.append("\n诊断：完整条件与逐项放宽后在曲库中都命中 0 首（不含任何排除），")
                    .append("说明曲库里确实没有这类歌。可以如实告诉用户没有，")
                    .append("或换个条件/关键词再查；不要用曲库外的歌名充数。");
            return sb.toString();
        }

        sb.append("没有找到同时满足 ").append(describeScope(artist, keyword, style)).append(" 的歌曲。");
        sb.append("\n诊断：完整条件在曲库中命中 ").append(exact).append(" 首（不含任何排除）。");
        if (!relaxed.isEmpty()) {
            sb.append("\n放宽条件后：").append(String.join("；", relaxed)).append("。");
            if (exact == 0) {
                sb.append("两个条件同时成立才为 0，说明是条件交集过窄，而不是曲库没有这类歌。")
                        .append("可以放宽一个条件重新检索，或直接按另一项条件推荐。");
            }
        }

        // ⑤ 排除项：会话历史与用户偏好。这是业务约束，必须让模型知道，但由它自己决定怎么讲。
        if (used != null && !used.isEmpty()) {
            sb.append("\n本会话近期歌单已用过 ").append(used.size()).append(" 首，这些歌不会重复出现。")
                    .append("如果用户接受复用旧歌，可以让他明确说「沿用刚才那些」，")
                    .append("再用 preparePlaylistCreation 并带上 allowReuse=true。");
        }
        if (exclusions != null && !exclusions.isEmpty()) {
            sb.append("\n另有用户偏好排除生效：").append(exclusions.excludedArtistIds().size())
                    .append(" 位歌手与 ").append(exclusions.excludedStyleNames().size()).append(" 种风格。")
                    .append("这些是用户设定的约束，最终推荐结果不得违反；")
                    .append("但你可以用其它方式探索（例如换一个条件、按歌手推荐），不必局限在当前条件里。");
        }
        sb.append("\n以上都是服务端查到的真实数量，你可以据此决定怎么回答用户。");
        return sb.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
