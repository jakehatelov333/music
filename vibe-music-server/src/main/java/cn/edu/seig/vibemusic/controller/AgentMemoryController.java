package cn.edu.seig.vibemusic.controller;

import cn.edu.seig.vibemusic.model.dto.MemoryPersonalizationRequest;
import cn.edu.seig.vibemusic.model.dto.MemoryPreferenceRequest;
import cn.edu.seig.vibemusic.model.vo.ClearAutoResultVO;
import cn.edu.seig.vibemusic.model.vo.MemoryOverviewVO;
import cn.edu.seig.vibemusic.model.vo.MemoryPreferenceVO;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.security.PlaylistAccessService;
import cn.edu.seig.vibemusic.service.agent.UserMemoryPreferenceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * AI 记忆与偏好管理。
 *
 * <p><b>身份边界：</b>所有端点都通过 {@code PlaylistAccessService.requireUserId()}
 * 从服务端认证上下文取当前用户 —— 客户端传入的任何用户标识一律不信任。
 * 未登录访问返回 401；访问他人数据按 404 处理（不泄漏存在性）。
 *
 * <p><b>路径说明：</b>挂在 {@code /ai/memory} 下，天然落在既有的
 * {@code /ai/**} 白名单与 {@code ROLE_USER} 路径权限内，无需改
 * {@code WebConfig} / {@code PathConstant}。
 */
@RestController
@RequestMapping("/ai/memory")
@RequiredArgsConstructor
public class AgentMemoryController {

    private final UserMemoryPreferenceService memory;

    /** 手动偏好 + 最终生效偏好 + 冲突 + 个性化开关，一次取全。 */
    @GetMapping("/overview")
    public Result<MemoryOverviewVO> overview() {
        return Result.success(memory.overview(PlaylistAccessService.requireUserId()));
    }

    @PostMapping("/preferences")
    public Result<MemoryPreferenceVO> createPreference(@Valid @RequestBody MemoryPreferenceRequest request) {
        return Result.success(memory.create(PlaylistAccessService.requireUserId(), request));
    }

    @PutMapping("/preferences/{id}")
    public Result<MemoryPreferenceVO> updatePreference(@PathVariable Long id,
                                                       @Valid @RequestBody MemoryPreferenceRequest request) {
        return Result.success(memory.update(PlaylistAccessService.requireUserId(), id, request));
    }

    @DeleteMapping("/preferences/{id}")
    public Result<String> deletePreference(@PathVariable Long id) {
        return Result.success(memory.delete(PlaylistAccessService.requireUserId(), id));
    }

    @PutMapping("/personalization")
    public Result<Boolean> setPersonalization(@Valid @RequestBody MemoryPersonalizationRequest request) {
        return Result.success(memory.setPersonalizationEnabled(PlaylistAccessService.requireUserId(),
                Boolean.TRUE.equals(request.getEnabled())));
    }

    /**
     * 清除 AI 自动生成的记忆（自动画像 + 会话摘要）。
     * 不删手动偏好、聊天消息、会话与待确认任务。
     */
    @PostMapping("/clear-auto")
    public Result<ClearAutoResultVO> clearAuto() {
        return Result.success(memory.clearAuto(PlaylistAccessService.requireUserId()));
    }

    /** 曲库派生的可选风格集合（供前端下拉）。 */
    @GetMapping("/styles")
    public Result<List<String>> styles() {
        PlaylistAccessService.requireUserId();
        Set<String> options = memory.styleOptions();
        return Result.success(List.copyOf(options));
    }
}
