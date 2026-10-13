package cn.edu.seig.vibemusic.tool;

import cn.edu.seig.vibemusic.model.agent.*;
import cn.edu.seig.vibemusic.model.vo.PendingActionVO;
import cn.edu.seig.vibemusic.service.agent.PendingActionService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 写工具（目前只有“创建歌单”）。
 *
 * <p><b>本工具不会创建任何歌单。</b>它只做三件事：校验参数、校验歌曲真实存在、
 * 落一条服务端待确认任务，然后把预览返回给模型与前端。真正的写入只发生在
 * 用户在界面上点击确认、且服务端重新鉴权通过之后（{@code PendingActionService#confirm}）。
 * 这是“先确认再写入”这条规则在工具层的落点。
 *
 * <p><b>身份不可伪造：</b>方法签名里没有用户 ID。用户身份来自
 * {@link AgentContext}（服务端 JWT 解析结果）。游客调用会拿到
 * {@code UNAUTHORIZED}，而不是被模型“自己编一个用户”。
 *
 * <p>与只读工具一样，本类通过 {@link #withContext} 返回新实例来绑定上下文，
 * 保证 Spring 单例被并发请求复用时互不干扰。
 *
 * <p><b>{@code allowReuse}：</b>服务端会拒绝与近期歌单歌曲完全相同的预览。
 * 唯一的放行信号是本工具的 {@code allowReuse} 参数 —— 它由模型根据<b>本轮</b>用户是否
 * 明确要求复用旧歌来填写，服务端不自行猜测，也不提供「永久复用」开关。
 */
@Slf4j
@Component
public class PlaylistActionTools {

    private final PendingActionService pendingActions;
    private final AgentContext context;

    @Autowired
    public PlaylistActionTools(PendingActionService pendingActions) {
        this(pendingActions, AgentContext.none());
    }

    public PlaylistActionTools(PendingActionService pendingActions, AgentContext context) {
        this.pendingActions = pendingActions;
        this.context = context == null ? AgentContext.none() : context;
    }

    public PlaylistActionTools withContext(AgentContext value) {
        return new PlaylistActionTools(pendingActions, value);
    }

    @Tool("为用户准备创建歌单。当用户提出「帮我建一个歌单」这类需求时，"
            + "必须先用搜索工具拿到真实歌曲的 songId，再调用本工具生成待确认预览。"
            + "本工具不会真正写入歌单，用户需要在界面上点击确认后才会创建。"
            + "参数：title 歌单名称；introduction 歌单简介，可为空；style 歌单风格，可为空；"
            + "songIds 歌曲 ID 列表，用英文逗号分隔，例如 \"101,102,103\"，"
            + "只能使用搜索工具返回过的真实 songId，不要编造；"
            + "allowReuse 只有在本轮用户明确要求「沿用/复用上一份歌单的歌曲」「保留这些歌」"
            + "或「重新生成原来那版」时才填 \"true\"，其余情况留空。"
            + "如果返回 code 为 REJECTED 且提示歌曲与近期歌单完全相同，"
            + "请换一批候选歌曲重新生成，不要原样重试。")
    public String preparePlaylistCreation(String title, String introduction, String style, String songIds,
                                          @P(value = "本轮用户是否明确要求复用旧歌；是则填 true，否则留空",
                                                  required = false) String allowReuse) {
        String toolName = "preparePlaylistCreation";
        String toolCallId = context.nextToolCallId(toolName);
        context.emit(AgentEvent.toolStart(context.taskId(), toolCallId, toolName, "正在准备歌单预览…"));
        long start = System.nanoTime();

        ToolResult<?> result;
        PendingActionVO preview = null;
        try {
            if (!context.authenticated()) {
                result = ToolResult.unauthorized("创建歌单需要先登录账号，请提示用户登录后再试");
            } else {
                List<Long> ids = parseSongIds(songIds);
                if (ids.isEmpty()) {
                    result = ToolResult.invalid("songIds 不能为空，请先用搜索工具获取真实歌曲 ID");
                } else {
                    preview = pendingActions.prepare(context.userId(), context.chatId(),
                            new PlaylistDraft(title, introduction, style, false, ids),
                            parseAllowReuse(allowReuse));
                    result = ToolResult.ok(AgentToolKinds.PLAYLIST_PREVIEW,
                            "已生成待确认的歌单预览：「" + preview.getTitle() + "」共 "
                                    + preview.getSongCount() + " 首歌曲。请在界面上点击确认后创建。",
                            preview, preview.getSongCount());
                }
            }
        } catch (ResponseStatusException e) {
            result = ToolResult.rejected("REJECTED", e.getReason() == null ? "歌单预览生成失败" : e.getReason());
        } catch (RuntimeException e) {
            log.warn("创建歌单预览失败 type={}", e.getClass().getSimpleName());
            result = ToolResult.error("生成歌单预览失败，请稍后重试");
        }

        long durationMs = (System.nanoTime() - start) / 1_000_000L;
        if (context.metrics() != null) {
            context.metrics().toolCalled();
            if (!result.ok()) context.metrics().toolFailed();
            if (preview != null) context.metrics().pendingAction(preview.getTaskId());
        }
        context.emit(AgentEvent.toolEnd(context.taskId(), toolCallId, toolName, result, durationMs));
        if (preview != null) {
            // 只有真正落到待确认状态时才推确认事件；前端据此渲染确认卡片。
            context.emit(AgentEvent.confirm(context.taskId(), toolCallId, preview));
        }
        return MusicTools.json(result);
    }

    /**
     * 解析模型给出的「本轮是否明确要求复用旧歌」信号。
     *
     * <p>只有明确的正向写法才算复用；留空、缺失或无法识别一律按「不复用」处理 ——
     * 宁可多让模型换一批歌，也不要在用户没要求的情况下放行完全重复的歌单。
     */
    public static boolean parseAllowReuse(String raw) {
        if (raw == null) return false;
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true", "1", "yes", "y", "是", "复用" -> true;
            default -> false;
        };
    }

    /** 兼容模型可能给出的几种写法：CSV、JSON 数组、空格分隔。 */
    public static List<Long> parseSongIds(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String cleaned = raw.replaceAll("[\\[\\]{}\"']", " ").trim();
        List<Long> ids = new ArrayList<>();
        for (String token : cleaned.split("[,\\s]+")) {
            if (token.isBlank()) continue;
            try {
                long id = Long.parseLong(token.trim());
                if (id > 0 && !ids.contains(id)) ids.add(id);
            } catch (NumberFormatException ignored) {
                // 非数字片段直接忽略，最终由 resolveSongs 判断是否全部有效。
            }
        }
        return ids;
    }
}
