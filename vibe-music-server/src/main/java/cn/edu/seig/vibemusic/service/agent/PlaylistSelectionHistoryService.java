package cn.edu.seig.vibemusic.service.agent;

import cn.edu.seig.vibemusic.mapper.AgentPendingActionMapper;
import cn.edu.seig.vibemusic.model.agent.PlaylistPreview;
import cn.edu.seig.vibemusic.model.agent.SongRef;
import cn.edu.seig.vibemusic.model.entity.AgentPendingAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 「这个会话最近用哪些歌建过歌单」的只读视图。
 *
 * <p><b>解决的问题：</b>用户在同一个对话里连续说「帮我建一个薛之谦的歌单」「再建一个」时，
 * 如果只靠模型记忆，第二轮很容易把第一轮的原班歌曲再挑一遍。这里把「近期用过的歌曲」
 * 变成一份服务端可查询的事实，供候选检索工具在数据库层排除。
 *
 * <p><b>数据来源：</b>复用现有的 {@code tb_agent_pending_action}，不新增表。
 * 每一条创建歌单任务里的 {@code preview_json} 都是「用户看到过的预览快照」，
 * 其中 {@code songs[].songId} 就是真实用过的歌曲 ID。
 *
 * <p><b>隔离性：</b>查询条件同时限定 {@code userId} 与 {@code chatId}，
 * 两者都来自服务端认证与请求参数，模型无法指定，因此不同用户、不同会话之间
 * 绝不会互相看到选曲历史。
 *
 * <p><b>容错：</b>单条预览 JSON 损坏只记日志并跳过，不影响本次对话；
 * 也不使用任何静态可变状态，历史每次都从数据库现算。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaylistSelectionHistoryService {

    /** 参考的历史预览条数上限：防止配置写错导致一次查询把整个会话历史都拉进来。 */
    public static final int MAX_LOOKBACK = 20;

    private final AgentPendingActionMapper actions;
    private final ObjectMapper json;

    /** 参考最近多少个已生成的创建歌单预览。可用 {@code ai.playlist-history.lookback} 调整。 */
    @Value("${ai.playlist-history.lookback:5}")
    private int lookback;

    /**
     * 近期预览里出现过的全部歌曲 ID（已去重）。
     *
     * <p>调用方（候选检索工具）把它交给 SQL 的 {@code NOT IN}，
     * 而不是在 Java 里对已经 LIMIT 过的结果做过滤 —— 后者会在排除后得到空集，
     * 从而错误地得出「没有新歌」的结论。
     *
     * @param userId 服务端认证得到的用户 ID；游客为 {@code null}，返回空集合
     * @param chatId 当前会话 ID；为空时返回空集合（无法判断会话就没有可信历史）
     */
    public Set<Long> recentSongIds(Long userId, String chatId) {
        Set<Long> merged = new LinkedHashSet<>();
        for (Set<Long> used : recentSongSets(userId, chatId)) {
            merged.addAll(used);
        }
        return merged;
    }

    /**
     * 新歌单的歌曲集合是否与近期某个预览<b>完全相同</b>。
     *
     * <p>比较前先规范化（去空、去重、排序），因此歌曲顺序不同不会被漏判；
     * 同时是「集合相等」而不是「有交集」，所以只重了一两首的改动不会被误判为整份重复。
     *
     * @return 完全相同返回 {@code true}；历史为空、参数为空或只有部分重合都返回 {@code false}
     */
    public boolean isDuplicateOfRecent(Long userId, String chatId, Collection<Long> songIds) {
        Set<Long> candidate = normalize(songIds);
        if (candidate.isEmpty()) return false;
        for (Set<Long> used : recentSongSets(userId, chatId)) {
            if (used.equals(candidate)) return true;
        }
        return false;
    }

    /**
     * 近期预览的歌曲集合列表，最近优先。解析失败或为空的预览会被跳过。
     * 供测试与需要逐条比较的调用方使用。
     */
    public List<Set<Long>> recentSongSets(Long userId, String chatId) {
        if (userId == null || chatId == null || chatId.isBlank()) return List.of();
        List<AgentPendingAction> rows = actions.findRecentByChat(userId, chatId,
                PendingActionService.ACTION_CREATE_PLAYLIST, effectiveLookback());
        List<Set<Long>> sets = new ArrayList<>(rows.size());
        for (AgentPendingAction row : rows) {
            Set<Long> used = parsePreviewSongIds(row);
            if (used != null && !used.isEmpty()) sets.add(used);
        }
        return sets;
    }

    // ------------------------------------------------------------------ helpers

    /** 只读取 preview_json 里的歌曲 ID；任何解析问题都只记日志，不向上抛。 */
    private Set<Long> parsePreviewSongIds(AgentPendingAction row) {
        String raw = row.getPreviewJson();
        if (raw == null || raw.isBlank()) return null;
        try {
            PlaylistPreview preview = json.readValue(raw, PlaylistPreview.class);
            if (preview == null || preview.songs() == null) return null;
            return normalize(preview.songs().stream().map(SongRef::songId).toList());
        } catch (Exception e) {
            log.warn("选曲历史预览解析失败，已跳过该条 taskId={} 类型={}",
                    row.getTaskId(), e.getClass().getSimpleName());
            return null;
        }
    }

    private int effectiveLookback() {
        if (lookback <= 0) return 1;
        return Math.min(lookback, MAX_LOOKBACK);
    }

    /** 规范化歌曲 ID：剔除 null、去重并排序，保证「同一组歌」无论顺序都能相等比较。 */
    private static Set<Long> normalize(Collection<Long> songIds) {
        if (songIds == null || songIds.isEmpty()) return Set.of();
        TreeSet<Long> normalized = new TreeSet<>();
        for (Long id : songIds) {
            if (Objects.nonNull(id)) normalized.add(id);
        }
        return normalized;
    }
}
