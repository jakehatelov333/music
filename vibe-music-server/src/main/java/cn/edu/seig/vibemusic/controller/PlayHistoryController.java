package cn.edu.seig.vibemusic.controller;

import cn.edu.seig.vibemusic.model.dto.PlayHistoryQueryDTO;
import cn.edu.seig.vibemusic.model.dto.PlayHistoryRecordDTO;
import cn.edu.seig.vibemusic.model.vo.PlayHistoryVO;
import cn.edu.seig.vibemusic.result.PageResult;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.PlayHistoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 「最近播放」接口。
 *
 * <p><b>全部要求登录。</b>本控制器刻意<b>不</b>出现在
 * {@code WebConfig#addInterceptors} 的 {@code excludePathPatterns} 里，也不在
 * {@code PathConstant} 的匿名白名单里 —— 未登录访问会由 {@code LoginInterceptor} 直接 401，
 * 根本进不到 Controller。历史是个人数据，没有「匿名也能看」的合理场景。
 *
 * <p><b>路径前缀 {@code /play-history/}</b> 与 {@code /favorite/} 同级，已在
 * {@code application.yml} 的 {@code role-path-permissions} 里登记给 {@code ROLE_USER}；
 * 管理员角色没有该前缀，符合「历史只属于用户自己」。
 *
 * <p><b>任何接口都不接受 userId 参数。</b>当前用户一律由
 * {@code PlayHistoryService} 从服务端认证上下文取，客户端无法指定读写对象。
 */
@RestController
@RequestMapping("/play-history")
@RequiredArgsConstructor
public class PlayHistoryController {

    private final PlayHistoryService playHistoryService;

    /**
     * 记录一次有效播放。
     *
     * <p>由播放器在「确实播到有效阈值」后调用（不是页面加载时）。
     * 同一首歌重复播放不会产生新记录，只刷新最近播放时间并累加次数。
     *
     * @return {@code added=false} 表示被幂等键判定为重复请求，未重复计数
     */
    @PostMapping("/record")
    public Result<Map<String, Object>> record(@Valid @RequestBody PlayHistoryRecordDTO input) {
        boolean counted = playHistoryService.recordPlay(input);
        return Result.success(counted ? "已记录播放" : "重复的播放上报已忽略",
                Map.of("counted", counted));
    }

    /**
     * 分页查询当前用户的播放历史，按最近播放时间倒序。
     */
    @PostMapping("/page")
    public Result<PageResult<PlayHistoryVO>> page(@Valid @RequestBody PlayHistoryQueryDTO query) {
        return Result.success(playHistoryService.page(query));
    }

    /**
     * 最近播放的歌曲（「刚刚听过」）。
     *
     * @param limit 条数，越界会被夹到合法区间而不是报错
     */
    @GetMapping("/recent")
    public Result<List<PlayHistoryVO>> recent(@RequestParam(defaultValue = "20")
                                              @Min(1) @Max(50) Integer limit) {
        return Result.success(playHistoryService.recent(limit));
    }

    /**
     * 删除当前用户的一条历史记录。
     *
     * <p>不属于当前用户的 id 与不存在的 id 都返回 404 —— 若前者返回 403，
     * 这个接口就变成了「某个 id 是否存在」的探测器。
     */
    @DeleteMapping("/{historyId}")
    public Result<Void> delete(@PathVariable Long historyId) {
        playHistoryService.delete(historyId);
        return Result.success();
    }

    /**
     * 清空当前用户的播放历史。只影响当前用户。
     *
     * <p><b>为什么是 {@code /clear} 而不是 {@code DELETE /play-history}：</b>
     * 角色鉴权用的是 {@code requestURI.startsWith(prefix)}，权限表里登记的是
     * {@code /play-history/}（带斜杠）。{@code DELETE /play-history} 不匹配这个前缀，
     * 登录用户会被 403 挡住。与其放宽权限前缀（会连带放行 {@code /play-historyXxx}），
     * 不如让所有接口都落在同一个前缀下 —— 也与项目既有的动作式路径
     * （{@code /favorite/cancelCollectSong}、{@code /user/getUserInfo}）保持一致。
     *
     * @return {@code cleared} 为实际删除条数
     */
    @DeleteMapping("/clear")
    public Result<Map<String, Object>> clear() {
        long cleared = playHistoryService.clear();
        return Result.success("已清空播放历史", Map.of("cleared", cleared));
    }
}
