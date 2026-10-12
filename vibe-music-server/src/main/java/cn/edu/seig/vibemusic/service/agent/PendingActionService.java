package cn.edu.seig.vibemusic.service.agent;

import cn.edu.seig.vibemusic.enumeration.PendingActionStatusEnum;
import cn.edu.seig.vibemusic.mapper.AgentPendingActionMapper;
import cn.edu.seig.vibemusic.model.agent.PlaylistDraft;
import cn.edu.seig.vibemusic.model.agent.PlaylistPreview;
import cn.edu.seig.vibemusic.model.agent.SongRef;
import cn.edu.seig.vibemusic.model.dto.AgentPlaylistDraftDTO;
import cn.edu.seig.vibemusic.model.dto.PersonalPlaylistCreateDTO;
import cn.edu.seig.vibemusic.model.entity.AgentPendingAction;
import cn.edu.seig.vibemusic.model.vo.PendingActionVO;
import cn.edu.seig.vibemusic.model.vo.PlaylistDetailVO;
import cn.edu.seig.vibemusic.security.PlaylistAccessService;
import cn.edu.seig.vibemusic.service.PersonalPlaylistService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 待确认动作的完整生命周期：准备 → （可修改）→ 确认执行 / 取消 / 过期。
 *
 * <p>本类承担第 10 项的四个硬要求：
 * <ol>
 *     <li><b>确认前不写入</b>：{@link #prepare} 只落一条待确认记录，绝不碰 {@code tb_playlist}；</li>
 *     <li><b>确认时重新鉴权</b>：{@link #confirm} 从当前请求的 JWT 重新取身份，
 *         并校验任务归属、状态、有效期、歌曲有效性与版本号；</li>
 *     <li><b>可靠幂等</b>：抢占用数据库条件更新，真正的创建复用
 *         {@link PersonalPlaylistService#create} 的 Idempotency-Key 收据，双保险；</li>
 *     <li><b>可查询</b>：{@link #get} / {@link #pending} 让刷新页面后仍能看到真实状态。</li>
 * </ol>
 *
 * <p><b>为什么不需要额外的事务注解：</b>抢占、执行、落结果三步各自独立提交。
 * 抢占先提交，其他并发请求才能立刻看到 EXECUTING 并走幂等重放；
 * 真正的创建在 {@code PersonalPlaylistService#create} 自己的事务里完成，
 * 歌单与歌曲关联要么一起成功、要么一起回滚。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PendingActionService {

    public static final String ACTION_CREATE_PLAYLIST = "CREATE_PLAYLIST";

    /** 单个待确认歌单最多容纳的歌曲数，与 DTO 校验保持一致。 */
    public static final int MAX_SONGS = 100;

    /**
     * EXECUTING 超过该秒数视为僵死（进程在“已抢占、未落结果”之间崩溃），
     * 允许后续请求重新抢占；重新执行时同一 taskId 仍然是幂等键，不会重复建歌单。
     */
    private static final long STALE_EXECUTING_SECONDS = 60;

    private final AgentPendingActionMapper actions;
    private final AgentSongQueryService songs;
    private final PersonalPlaylistService personalPlaylists;
    private final ObjectMapper json;

    @Value("${ai.pending-action-ttl-minutes:30}")
    private int ttlMinutes;

    /**
     * 生成待确认任务。只校验、只落库，不写任何业务数据。
     *
     * <p>{@code userId} 由调用方（Agent 工具）从 {@code AgentContext} 注入，
     * 该值最终来源是服务端 JWT 解析，模型无法指定。
     */
    public PendingActionVO prepare(Long userId, String chatId, PlaylistDraft rawDraft) {
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录后再创建歌单");
        PlaylistDraft draft = rawDraft.normalized();
        validateDraft(draft);
        List<SongRef> resolved = resolveSongs(draft.songIds());

        LocalDateTime now = LocalDateTime.now();
        AgentPendingAction action = new AgentPendingAction();
        action.setTaskId(UUID.randomUUID().toString());
        action.setUserId(userId);
        action.setChatId(chatId == null ? "" : chatId);
        action.setActionType(ACTION_CREATE_PLAYLIST);
        action.setVersion(1);
        action.setStatus(PendingActionStatusEnum.PENDING.getStatus());
        action.setCreatedAt(now);
        action.setUpdatedAt(now);
        action.setExpiresAt(now.plusMinutes(Math.max(1, ttlMinutes)));
        action.setDraftJson(write(draft));
        action.setPreviewJson(write(preview(draft, resolved, 1)));
        actions.insert(action);
        return toVO(action, resolved);
    }

    /** 用户在确认前修改名称或歌曲列表。带乐观锁版本号，防止两个标签页互相覆盖。 */
    public PendingActionVO update(String taskId, AgentPlaylistDraftDTO input) {
        Long userId = PlaylistAccessService.requireUserId();
        AgentPendingAction action = requireOwned(taskId, userId);
        expireIfNeeded(action);
        requirePending(action, "修改");
        PlaylistDraft draft = new PlaylistDraft(input.getTitle(), input.getIntroduction(), input.getStyle(),
                Boolean.TRUE.equals(input.getIsPublic()), input.getSongIds()).normalized();
        validateDraft(draft);
        List<SongRef> resolved = resolveSongs(draft.songIds());
        int nextVersion = action.getVersion() + 1;
        int updated = actions.updateDraft(taskId, userId, write(draft),
                write(preview(draft, resolved, nextVersion)), nextVersion, input.getVersion(), LocalDateTime.now());
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "预览内容已被其他操作更新，请刷新后重试");
        }
        return toVO(actions.findOwned(taskId, userId), resolved);
    }

    /**
     * 用户点击确认。这是唯一会真正写入歌单的入口。
     *
     * <p>{@code expectedVersion} 可空；传入时会校验，防止“确认的是旧预览、执行的是新内容”。
     */
    public PendingActionVO confirm(String taskId, Integer expectedVersion) {
        Long userId = PlaylistAccessService.requireUserId();
        AgentPendingAction action = requireOwned(taskId, userId);
        LocalDateTime now = LocalDateTime.now();
        expireIfNeeded(action);

        if (PendingActionStatusEnum.CANCELLED.getStatus().equals(action.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该任务已取消，无法执行");
        }
        if (PendingActionStatusEnum.EXPIRED.getStatus().equals(action.getStatus())) {
            throw new ResponseStatusException(HttpStatus.GONE, "该任务已过期，请重新发起创建");
        }
        // 终态直接重放已保存的结果，而不是再次创建。
        if (PendingActionStatusEnum.SUCCEEDED.getStatus().equals(action.getStatus())
                || PendingActionStatusEnum.FAILED.getStatus().equals(action.getStatus())) {
            return toVO(action, null);
        }
        if (expectedVersion != null && !expectedVersion.equals(action.getVersion())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "预览内容已更新，请刷新后重新确认");
        }
        if (PendingActionStatusEnum.EXECUTING.getStatus().equals(action.getStatus())
                && action.getUpdatedAt() != null
                && action.getUpdatedAt().isAfter(now.minusSeconds(STALE_EXECUTING_SECONDS))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该任务正在执行中，请稍候");
        }

        int claimed = actions.claim(taskId, userId, now, now.minusSeconds(STALE_EXECUTING_SECONDS));
        if (claimed != 1) {
            // 并发确认输给了另一个请求：返回对方已落库的结果，绝不重复创建。
            AgentPendingAction latest = actions.findOwned(taskId, userId);
            if (latest != null && latest.getResultPlaylistId() != null) return toVO(latest, null);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该任务正在执行中，请稍候刷新");
        }

        try {
            PlaylistDraft draft = readDraft(action);
            PersonalPlaylistCreateDTO dto = new PersonalPlaylistCreateDTO();
            dto.setTitle(draft.title());
            dto.setIntroduction(draft.introduction());
            dto.setStyle(draft.style());
            dto.setIsPublic(draft.isPublic());
            dto.setSongIds(draft.songIds());
            // 幂等键 = 任务 ID：即使本进程在“已创建、未落结果”之间崩溃，
            // 重试也会命中创建收据并返回同一个歌单，而不是再建一个。
            PlaylistDetailVO created = personalPlaylists.create(taskId, dto);
            actions.complete(taskId, created.getPlaylistId(), LocalDateTime.now());
            log.info("待确认任务执行成功 taskId={} playlistId={}", taskId, created.getPlaylistId());
            return toVO(actions.findOwned(taskId, userId), null);
        } catch (ResponseStatusException e) {
            actions.fail(taskId, String.valueOf(e.getStatusCode().value()), e.getReason(), LocalDateTime.now());
            throw e;
        } catch (RuntimeException e) {
            log.warn("待确认任务执行失败 taskId={} type={}", taskId, e.getClass().getSimpleName());
            actions.fail(taskId, "INTERNAL_ERROR", "创建歌单失败，请稍后重试", LocalDateTime.now());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "创建歌单失败，请稍后重试");
        }
    }

    public PendingActionVO cancel(String taskId) {
        Long userId = PlaylistAccessService.requireUserId();
        AgentPendingAction action = requireOwned(taskId, userId);
        LocalDateTime now = LocalDateTime.now();
        expireIfNeeded(action);
        if (PendingActionStatusEnum.PENDING.getStatus().equals(action.getStatus())) {
            actions.cancel(taskId, userId, now);
        } else if (!PendingActionStatusEnum.CANCELLED.getStatus().equals(action.getStatus())) {
            // 已在执行或已有结果：取消无意义，如实告知而不是假装取消成功。
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该任务当前状态（" + action.getStatus() + "）无法取消");
        }
        return toVO(actions.findOwned(taskId, userId), null);
    }

    /** 查询单个任务。归属校验失败一律返回 404，不泄漏“这个任务存在但不属于你”。 */
    public PendingActionVO get(String taskId) {
        Long userId = PlaylistAccessService.requireUserId();
        AgentPendingAction action = requireOwned(taskId, userId);
        if (expireIfNeeded(action)) action = actions.findOwned(taskId, userId);
        return toVO(action, null);
    }

    /**
     * 当前用户仍待确认的任务，供页面刷新后重建确认卡片。
     * {@code chatId} 为空时返回该用户的全部待确认任务（换设备也能找回）。
     */
    public List<PendingActionVO> pending(String chatId) {
        Long userId = PlaylistAccessService.requireUserId();
        LocalDateTime now = LocalDateTime.now();
        actions.expireAll(now);
        List<AgentPendingAction> found = (chatId == null || chatId.isBlank())
                ? actions.findPendingByUser(userId, now)
                : actions.findPending(userId, chatId, now);
        return found.stream().map(action -> toVO(action, null)).toList();
    }

    // ------------------------------------------------------------------ helpers

    private AgentPendingAction requireOwned(String taskId, Long userId) {
        if (taskId == null || taskId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少任务标识");
        }
        AgentPendingAction action = actions.findOwned(taskId, userId);
        if (action == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在或无权访问");
        return action;
    }

    private boolean expireIfNeeded(AgentPendingAction action) {
        if (!PendingActionStatusEnum.PENDING.getStatus().equals(action.getStatus())) return false;
        LocalDateTime now = LocalDateTime.now();
        if (action.getExpiresAt() != null && !action.getExpiresAt().isAfter(now)) {
            actions.expire(action.getTaskId(), now);
            action.setStatus(PendingActionStatusEnum.EXPIRED.getStatus());
            return true;
        }
        return false;
    }

    private void requirePending(AgentPendingAction action, String verb) {
        if (!PendingActionStatusEnum.PENDING.getStatus().equals(action.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "任务当前状态（" + action.getStatus() + "）不允许" + verb);
        }
    }

    private void validateDraft(PlaylistDraft draft) {
        if (draft.title() == null || draft.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "歌单名称不能为空");
        }
        if (draft.title().length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "歌单名称不能超过 255 字");
        }
        if (draft.songIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "歌单至少需要一首歌曲");
        }
        if (draft.songIds().size() > MAX_SONGS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单个歌单最多 " + MAX_SONGS + " 首歌");
        }
    }

    /** 只接受数据库里真实存在的歌曲 ID，并保持顺序稳定。 */
    private List<SongRef> resolveSongs(List<Long> songIds) {
        List<SongRef> resolved = songs.byIds(songIds);
        if (resolved.size() != songIds.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "部分歌曲已不存在，请重新选择");
        }
        return resolved;
    }

    private PlaylistPreview preview(PlaylistDraft draft, List<SongRef> resolved, int version) {
        return new PlaylistPreview(draft.title(), draft.introduction(), draft.style(), draft.isPublic(),
                resolved.size(), resolved, version);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("待确认任务序列化失败", e);
        }
    }

    private PlaylistDraft readDraft(AgentPendingAction action) {
        try {
            return json.readValue(action.getDraftJson(), PlaylistDraft.class).normalized();
        } catch (Exception e) {
            throw new IllegalStateException("待确认任务数据损坏", e);
        }
    }

    private PendingActionVO toVO(AgentPendingAction action, List<SongRef> resolvedSongs) {
        PendingActionVO vo = new PendingActionVO();
        vo.setTaskId(action.getTaskId());
        vo.setActionType(action.getActionType());
        vo.setStatus(action.getStatus());
        vo.setStatusDescription(describe(action.getStatus()));
        vo.setVersion(action.getVersion());
        vo.setPlaylistId(action.getResultPlaylistId());
        vo.setErrorCode(action.getErrorCode());
        vo.setErrorMessage(action.getErrorMessage());
        vo.setCreatedAt(action.getCreatedAt());
        vo.setExpiresAt(action.getExpiresAt());
        try {
            PlaylistPreview preview = json.readValue(action.getPreviewJson(), PlaylistPreview.class);
            vo.setTitle(preview.title());
            vo.setIntroduction(preview.introduction());
            vo.setStyle(preview.style());
            vo.setIsPublic(preview.isPublic());
            vo.setSongCount(preview.songCount());
            vo.setSongs(resolvedSongs != null ? resolvedSongs : preview.songs());
        } catch (Exception e) {
            log.warn("待确认任务预览解析失败 taskId={}", action.getTaskId());
        }
        return vo;
    }

    private static String describe(String status) {
        try {
            return PendingActionStatusEnum.valueOf(status).getDescription();
        } catch (RuntimeException e) {
            return status;
        }
    }
}
