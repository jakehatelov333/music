package cn.edu.seig.vibemusic.controller;

import cn.edu.seig.vibemusic.model.dto.AiTraceQueryDTO;
import cn.edu.seig.vibemusic.model.vo.AiOverviewVO;
import cn.edu.seig.vibemusic.model.vo.AiTraceDetailVO;
import cn.edu.seig.vibemusic.model.vo.AiTraceListItemVO;
import cn.edu.seig.vibemusic.result.PageResult;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.agent.trace.AiTraceQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 管理端 AI 可观测性接口。
 *
 * <p><b>权限：</b>所有路径以 {@code /admin/} 开头，由 {@code LoginInterceptor} 校验 token
 * 后交给 {@code RolePermissionManager}，只允许 {@code ROLE_ADMIN}
 * （见 {@code application.yml} 的 {@code role-path-permissions}）。
 * 普通用户拿到 403，不存在"改个 traceId 看别人记录"的入口。
 *
 * <p><b>为什么单独一个控制器而不是塞进 {@code AdminController}：</b>后者已近 600 行且
 * 混着多种业务；可观测性是独立子系统，单独成文件便于审阅与后续扩展。
 *
 * <p><b>时间参数</b>用 ISO-8601（{@code 2026-10-24T00:00:00}）接收，
 * 避免前后端对"本地时间字符串"的时区理解不一致。
 */
@RestController
@RequestMapping("/admin/ai")
@RequiredArgsConstructor
public class AdminAiTraceController {

    private final AiTraceQueryService traces;

    /** 用量总览：指标、趋势、模型/用户/工具排行、慢请求、失败分布。 */
    @GetMapping("/overview")
    public Result<AiOverviewVO> overview(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return Result.success(traces.overview(from, to));
    }

    /** Trace 检索：组合条件 + 服务端分页 + 白名单排序。 */
    @PostMapping("/traces")
    public Result<PageResult<AiTraceListItemVO>> traces(@RequestBody(required = false) AiTraceQueryDTO query) {
        return Result.success(traces.search(query));
    }

    /** 筛选下拉的可选值：模型/状态取自真实数据，工具包含全部已注册工具。 */
    @GetMapping("/filter-options")
    public Result<Map<String, Object>> filterOptions(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return Result.success(traces.filterOptions(from, to));
    }

    /** Trace 详情：汇总 + 执行树 + 每次模型调用与工具调用明细。 */
    @GetMapping("/traces/{traceId}")
    public Result<AiTraceDetailVO> detail(@PathVariable String traceId) {
        return Result.success(traces.detail(traceId));
    }

    /** 执行树节点明细（按需加载，避免详情首屏传输过多内容）。 */
    @GetMapping("/traces/{traceId}/spans/{spanId}")
    public Result<Map<String, Object>> span(@PathVariable String traceId, @PathVariable String spanId) {
        return Result.success(traces.spanDetail(traceId, spanId));
    }

    /** 用户下拉：按账号前缀或精确 ID 查询，不加载全表。 */
    @GetMapping("/users/options")
    public Result<List<Map<String, Object>>> userOptions(@RequestParam(required = false) String keyword) {
        return Result.success(traces.userOptions(keyword));
    }
}
