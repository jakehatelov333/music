package cn.edu.seig.vibemusic.controller;

import cn.edu.seig.vibemusic.model.dto.AgentPlaylistDraftDTO;
import cn.edu.seig.vibemusic.model.vo.PendingActionVO;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.agent.PendingActionService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 待确认任务的查询与确认入口。
 *
 * <p>路径挂在 {@code /ai/} 下以复用既有的角色-路径权限（ROLE_USER 拥有 {@code /ai/} 前缀）。
 * 虽然 {@code /ai/**} 对匿名开放，但本控制器的每个方法都会在服务层调用
 * {@code PlaylistAccessService#requireUserId()}，游客一律 401 ——
 * 也就是说“能到达控制器”不等于“能操作任务”，权限判定始终在服务端。
 */
@RestController
@RequestMapping("/ai/actions")
@RequiredArgsConstructor
public class AgentActionController {

    private final PendingActionService actions;

    @ModelAttribute
    public void preventCaching(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    /** 当前会话下仍待确认的任务；页面刷新后用它重建确认卡片。 */
    @GetMapping("/pending")
    public Result<List<PendingActionVO>> pending(@RequestParam(required = false) String chatId) {
        return Result.success(actions.pending(chatId));
    }

    /** 查询任务真实状态与执行结果。 */
    @GetMapping("/{taskId}")
    public Result<PendingActionVO> get(@PathVariable String taskId) {
        return Result.success(actions.get(taskId));
    }

    /** 确认前修改歌单名称或歌曲列表。 */
    @PutMapping("/{taskId}")
    public Result<PendingActionVO> update(@PathVariable String taskId,
                                          @Valid @RequestBody AgentPlaylistDraftDTO input) {
        return Result.success(actions.update(taskId, input));
    }

    /**
     * 确认并执行。{@code version} 可选，传入时会校验预览版本，
     * 防止“确认的是旧预览、执行的是新内容”。
     */
    @PostMapping("/{taskId}/confirm")
    public Result<PendingActionVO> confirm(@PathVariable String taskId,
                                           @RequestParam(required = false) Integer version) {
        return Result.success(actions.confirm(taskId, version));
    }

    @PostMapping("/{taskId}/cancel")
    public Result<PendingActionVO> cancel(@PathVariable String taskId) {
        return Result.success(actions.cancel(taskId));
    }
}
