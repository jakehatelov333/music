package cn.edu.seig.vibemusic.service.agent.trace;

import cn.edu.seig.vibemusic.mapper.AiTaskMetricMapper;
import cn.edu.seig.vibemusic.mapper.AiTraceMapper;
import cn.edu.seig.vibemusic.mapper.AiTraceSearchMapper;
import cn.edu.seig.vibemusic.model.agent.AgentJson;
import cn.edu.seig.vibemusic.model.dto.AiCostTierRule;
import cn.edu.seig.vibemusic.model.dto.AiTraceQueryDTO;
import cn.edu.seig.vibemusic.model.entity.AiTaskMetric;
import cn.edu.seig.vibemusic.model.entity.AiTraceLlmCall;
import cn.edu.seig.vibemusic.model.entity.AiTraceSpan;
import cn.edu.seig.vibemusic.model.entity.AiTraceToolCall;
import cn.edu.seig.vibemusic.model.vo.*;
import cn.edu.seig.vibemusic.result.PageResult;
import cn.edu.seig.vibemusic.util.SortWhitelistUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 管理端 AI 可观测性查询。
 *
 * <p><b>权限：</b>本类不做权限判断 —— 它在 {@code AdminAiTraceController} 中被调用，
 * 而该控制器的所有路径都在 {@code /admin/} 前缀下，由 {@code LoginInterceptor} +
 * {@code RolePermissionManager} 强制 {@code ROLE_ADMIN}。普通用户访问会被 403，
 * 不存在"改个 traceId 就能看到别人的记录"的路径。
 *
 * <p><b>不扩大普通用户接口权限：</b>{@code /ai/chat/metrics} 仍然只返回当前用户自己的
 * 指标（见 {@code AgentMetricsRecorder#recentForUser}），与本类无关。
 *
 * <p><b>查询边界：</b>时间区间有硬上限（默认 31 天）、分页大小有上限，
 * 避免一次请求把整张表读进内存。所有 SQL 参数绑定，排序走白名单。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiTraceQueryService {

    /** 单次查询允许的最大时间跨度。 */
    private static final Duration MAX_RANGE = Duration.ofDays(31);
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_SLOWEST = 10;
    private static final int MAX_USER_RANKING = 20;

    /**
     * 排序白名单：前端字段 -> SQL 列表达式。
     * 未命中一律回退默认排序，绝不把前端字符串拼进 ORDER BY。
     */
    private static final Map<String, String> SORT_WHITELIST = Map.of(
            "startedAt", "m.created_at",
            "latencyMs", "m.latency_ms",
            "totalTokens", "(COALESCE(m.input_tokens, 0) + COALESCE(m.output_tokens, 0))",
            "toolCalls", "m.tool_calls",
            "modelCalls", "m.model_calls");

    private final AiTraceMapper traceMapper;
    private final AiTraceSearchMapper searchMapper;
    private final AiTaskMetricMapper metricMapper;
    private final AiCostCalculator costs;
    private final AgentTraceSettings settings;
    private final AgentTraceRecorder recorder;

    // ------------------------------------------------------------------ 总览

    /**
     * 用量总览。
     *
     * @param from 起始时间；为空默认最近 7 天
     * @param to   结束时间（不含）；为空默认现在
     */
    public AiOverviewVO overview(LocalDateTime from, LocalDateTime to) {
        Range range = normalize(from, to);
        AiOverviewVO overview = new AiOverviewVO();
        overview.setFrom(format(range.from()));
        overview.setTo(format(range.to()));
        overview.setScope("统计范围：Agent 主对话任务（tb_ai_task_metric）。"
                + "不含会话摘要、画像概述等后台模型调用，因此不等同于全站所有 AI 功能的总用量。");
        overview.setTraceEnabled(settings.enabled());
        overview.setTraceContentCaptured(settings.captureContent());
        overview.setTraceCaptureFailures(recorder == null ? 0 : recorder.failures());

        AiOverviewVO totals = traceMapper.totals(range.from(), range.to());
        if (totals != null) {
            overview.setTotalRequests(totals.getTotalRequests());
            overview.setSuccessRequests(totals.getSuccessRequests());
            overview.setTimeoutRequests(totals.getTimeoutRequests());
            overview.setInterruptedRequests(totals.getInterruptedRequests());
            overview.setTokenUnavailable(totals.getTokenUnavailable());
            overview.setInputTokens(totals.getInputTokens());
            overview.setOutputTokens(totals.getOutputTokens());
            overview.setModelCalls(totals.getModelCalls());
            overview.setToolCalls(totals.getToolCalls());
            overview.setToolFailures(totals.getToolFailures());
            overview.setAvgLatencyMs(totals.getAvgLatencyMs());
            overview.setGuardedRequests(totals.getGuardedRequests());
        }
        long failed = traceMapper.countFailed(range.from(), range.to());
        overview.setFailedRequests(failed);
        long input = overview.getInputTokens() == null ? 0 : overview.getInputTokens();
        long output = overview.getOutputTokens() == null ? 0 : overview.getOutputTokens();
        overview.setTotalTokens(input + output);
        overview.setP50LatencyMs(traceMapper.percentile(range.from(), range.to(), 0.50));
        overview.setP95LatencyMs(traceMapper.percentile(range.from(), range.to(), 0.95));

        if (overview.getTotalRequests() > 0) {
            overview.setGuardedRate(BigDecimal.valueOf(overview.getGuardedRequests())
                    .divide(BigDecimal.valueOf(overview.getTotalRequests()), 4, RoundingMode.HALF_UP));
        } else {
            overview.setGuardedRate(BigDecimal.ZERO);
        }
        overview.setCost(costFor(range));

        // 跨度超过 2 天按天聚合，否则按小时 —— 避免小时粒度在长区间里产生上千个点。
        String unit = Duration.between(range.from(), range.to()).toDays() >= 2 ? "DAY" : "HOUR";
        overview.setTrendGranularity(unit);
        // 一次查询同时得到请求数、输入/输出 Token 与护栏命中数，趋势与护栏趋势共用结果。
        List<AiOverviewVO.TrendPoint> trend = traceMapper.trend(range.from(), range.to(), unit);
        overview.setTrend(trend);
        overview.setGuardTrend(trend);

        overview.setModels(modelStats(range));
        overview.setUsers(userStats(range));
        overview.setTools(toolStats(range));
        overview.setFailures(traceMapper.failureStats(range.from(), range.to()));
        overview.setSlowest(slowest(range));
        return overview;
    }

    private List<AiOverviewVO.ModelStat> modelStats(Range range) {
        List<AiOverviewVO.ModelStat> stats = new ArrayList<>();
        Map<String, List<AiCostCalculator.TierUsage>> costsByModel = costGroupsByModel(range);
        for (Map<String, Object> row : traceMapper.modelStats(range.from(), range.to())) {
            AiOverviewVO.ModelStat stat = new AiOverviewVO.ModelStat();
            stat.setModel(str(row.get("model")));
            long requests = num(row.get("requests"));
            long failures = num(row.get("failureCount"));
            long input = num(row.get("inputTokens"));
            long output = num(row.get("outputTokens"));
            stat.setRequests(requests);
            stat.setInputTokens(input);
            stat.setOutputTokens(output);
            stat.setAvgLatencyMs(num(row.get("avgLatencyMs")));
            stat.setFailureRate(rate(failures, requests));
            AiCostVO cost = costs.estimateGroups(costsByModel.get(stat.getModel()));
            stat.setCost(cost.getAmount());
            stat.setCostConfigured(cost.isConfigured() && cost.getAmount() != null);
            stats.add(stat);
        }
        return stats;
    }

    /**
     * 费用总览按 MODEL Span 的真实单次用量分档。
     *
     * <p>任务汇总表只有「一次请求的总 Token」，无法还原每次模型调用各自落在哪一档；
     * 因此费用与模型费用都从 MODEL Span 聚合，并在文案中明确这个口径。
     * 没有 Trace 明细的历史请求不会伪造费用，单独计入 {@code unpricedCalls}。
     */
    private AiCostVO costFor(Range range) {
        List<AiCostTierRule> rules = costs.rules();
        List<AiCostCalculator.TierUsage> groups = new ArrayList<>();
        if (!rules.isEmpty()) {
            for (Map<String, Object> row : traceMapper.costTierStats(range.from(), range.to(), rules)) {
                int tierIndex = (int) number(value(row, "tierIndex"));
                boolean tokenAvailable = "PROVIDER".equals(str(value(row, "tokenSource")));
                long calls = num(value(row, "calls"));
                groups.add(new AiCostCalculator.TierUsage(tierIndex, calls,
                        nullableNum(value(row, "inputTokens")), nullableNum(value(row, "outputTokens")),
                        tokenAvailable));
            }
        }
        AiCostVO cost = costs.estimateGroups(groups);
        // 没有 Trace 的请求仍存在于任务指标中，不能静默地从费用里消失。
        long totalModelCalls = traceMapper.countModelCalls(range.from(), range.to());
        if (totalModelCalls > cost.getPricedCalls()) {
            cost.setUnpricedCalls(cost.getUnpricedCalls() + totalModelCalls - cost.getPricedCalls());
            cost.setNote(cost.getNote() + "；另有 " + (totalModelCalls - cost.getPricedCalls())
                    + " 次模型调用没有 Trace 明细，未计入");
        }
        return cost;
    }

    private Map<String, List<AiCostCalculator.TierUsage>> costGroupsByModel(Range range) {
        Map<String, List<AiCostCalculator.TierUsage>> result = new HashMap<>();
        List<AiCostTierRule> rules = costs.rules();
        if (rules.isEmpty()) return result;
        for (Map<String, Object> row : traceMapper.costTierStats(range.from(), range.to(), rules)) {
            String model = str(row.get("model"));
            if (model == null) continue;
            boolean tokenAvailable = "PROVIDER".equals(str(value(row, "tokenSource")));
            result.computeIfAbsent(model, key -> new ArrayList<>())
                    .add(new AiCostCalculator.TierUsage((int) number(value(row, "tierIndex")),
                            num(value(row, "calls")), nullableNum(value(row, "inputTokens")),
                            nullableNum(value(row, "outputTokens")), tokenAvailable));
        }
        return result;
    }

    private List<AiOverviewVO.UserStat> userStats(Range range) {
        List<AiOverviewVO.UserStat> stats = new ArrayList<>();
        for (Map<String, Object> row : traceMapper.userStats(range.from(), range.to(), MAX_USER_RANKING)) {
            AiOverviewVO.UserStat stat = new AiOverviewVO.UserStat();
            stat.setUserId(row.get("userId") == null ? null : num(row.get("userId")));
            stat.setUsername(str(row.get("username")));
            long requests = num(row.get("requests"));
            long tokens = num(row.get("totalTokens"));
            stat.setRequests(requests);
            stat.setTotalTokens(tokens);
            stat.setAvgTokensPerRequest(requests == 0 ? 0 : tokens / requests);
            stats.add(stat);
        }
        return stats;
    }

    private List<AiOverviewVO.ToolStat> toolStats(Range range) {
        List<AiOverviewVO.ToolStat> stats = new ArrayList<>();
        for (Map<String, Object> row : traceMapper.toolStats(range.from(), range.to())) {
            AiOverviewVO.ToolStat stat = new AiOverviewVO.ToolStat();
            stat.setToolName(str(row.get("toolName")));
            long calls = num(row.get("calls"));
            long failures = num(row.get("failures"));
            stat.setCalls(calls);
            stat.setFailures(failures);
            stat.setSuccessRate(rate(calls - failures, calls));
            stat.setFailureRate(rate(failures, calls));
            stat.setAvgDurationMs(num(row.get("avgDurationMs")));
            stats.add(stat);
        }
        return stats;
    }

    private List<AiOverviewVO.SlowRequest> slowest(Range range) {
        List<AiOverviewVO.SlowRequest> rows = traceMapper.slowest(range.from(), range.to(), MAX_SLOWEST);
        // 用户名单独补齐，避免在慢请求 SQL 里再 JOIN 一次用户表。
        Map<Long, String> names = new HashMap<>();
        for (AiOverviewVO.SlowRequest row : rows) {
            if (row.getUserId() == null) continue;
            names.computeIfAbsent(row.getUserId(), id -> {
                List<Map<String, Object>> found = searchMapper.searchUsers(String.valueOf(id), 1);
                return found.isEmpty() ? null : str(found.get(0).get("username"));
            });
            row.setUsername(names.get(row.getUserId()));
        }
        return rows;
    }

    // ------------------------------------------------------------------ 检索

    public PageResult<AiTraceListItemVO> search(AiTraceQueryDTO query) {
        AiTraceQueryDTO safe = query == null ? new AiTraceQueryDTO() : query;
        Range range = normalize(safe.getFrom(), safe.getTo());
        safe.setFrom(range.from());
        safe.setTo(range.to());

        int pageNum = safe.getPageNum() == null || safe.getPageNum() < 1 ? 1 : safe.getPageNum();
        int pageSize = safe.getPageSize() == null ? 20 : Math.min(Math.max(safe.getPageSize(), 1), MAX_PAGE_SIZE);
        safe.setPageNum(pageNum);
        safe.setPageSize(pageSize);

        // 账号搜索在后端完成：先把账号映射成 userId 集合，再进 SQL。
        List<Long> userIds = null;
        if (safe.getUsername() != null && !safe.getUsername().isBlank()) {
            userIds = new ArrayList<>();
            for (Map<String, Object> row : searchMapper.searchUsers(safe.getUsername().trim(), 50)) {
                userIds.add(num(row.get("userId")));
            }
            if (userIds.isEmpty()) {
                // 账号不存在时直接返回空页，而不是退化成"不过滤"把全表交出去。
                return new PageResult<>(0L, List.of());
            }
        }

        // 工具名过滤：先查 trace_id 集合，再作为 IN 条件。集合为空时同样返回空页。
        List<String> toolTraceIds = null;
        if (safe.getToolName() != null && !safe.getToolName().isBlank()) {
            toolTraceIds = traceMapper.traceIdsByToolName(safe.getToolName().trim(), safe.getFrom(), safe.getTo());
            if (toolTraceIds.isEmpty()) return new PageResult<>(0L, List.of());
        }

        String orderBy = SortWhitelistUtil.toOrderBy(safe.getSortField(), safe.getOrder(),
                SORT_WHITELIST, "m.created_at DESC");
        long total = searchMapper.count(safe, userIds, toolTraceIds);
        if (total == 0) return new PageResult<>(0L, List.of());
        int offset = (pageNum - 1) * pageSize;
        List<AiTraceListItemVO> items = searchMapper.search(safe, userIds, toolTraceIds, orderBy, offset, pageSize);
        return new PageResult<>(total, items);
    }

    /** 用户下拉选项：按账号前缀或精确 ID 搜索，不加载全表。 */
    public List<Map<String, Object>> userOptions(String keyword) {
        String value = keyword == null ? "" : keyword.trim();
        return searchMapper.searchUsers(value, 20);
    }

    /** 可用于筛选的模型 / 工具名（取自实际数据，不硬编码）。 */
    public Map<String, Object> filterOptions(LocalDateTime from, LocalDateTime to) {
        Range range = normalize(from, to);
        Map<String, Object> options = new LinkedHashMap<>();
        List<String> models = new ArrayList<>();
        for (Map<String, Object> row : traceMapper.modelStats(range.from(), range.to())) {
            String model = str(row.get("model"));
            if (model != null) models.add(model);
        }
        options.put("models", models);
        List<String> tools = new ArrayList<>();
        for (Map<String, Object> row : traceMapper.toolStats(range.from(), range.to())) {
            String tool = str(row.get("toolName"));
            if (tool != null) tools.add(tool);
        }
        options.put("tools", tools);
        options.put("statuses", List.of("SUCCESS", "FAILED", "TIMEOUT", "INTERRUPTED",
                "MODEL_ERROR", "TOOL_ERROR", "BUSINESS_ERROR", "UNKNOWN", "GUARDED"));
        options.put("failureCategories", List.of("MODEL_ERROR", "TOOL_ERROR", "TIMEOUT",
                "INTERRUPTED", "BUSINESS_ERROR", "UNKNOWN"));
        return options;
    }

    // ------------------------------------------------------------------ 详情

    public AiTraceDetailVO detail(String traceId) {
        if (traceId == null || traceId.isBlank()) throw notFound();
        AiTaskMetric metric = metricMapper.findByTaskId(traceId);
        List<AiTraceSpan> spans = traceMapper.findSpans(traceId);
        if (metric == null && spans.isEmpty()) throw notFound();

        AiTraceDetailVO detail = new AiTraceDetailVO();
        detail.setTraceId(traceId);
        if (metric != null) {
            detail.setChatId(metric.getChatId());
            detail.setUserId(metric.getUserId());
            detail.setModel(metric.getModel());
            detail.setStatus(Boolean.TRUE.equals(metric.getSuccess()) ? "SUCCESS"
                    : metric.getFailureCategory() == null ? "FAILED" : metric.getFailureCategory());
            detail.setFailureCategory(metric.getFailureCategory());
            detail.setFailureMessage(metric.getFailureMessage());
            detail.setLatencyMs(metric.getLatencyMs());
            detail.setInputTokens(metric.getInputTokens());
            detail.setOutputTokens(metric.getOutputTokens());
            detail.setTokenSource(metric.getTokenSource());
            detail.setModelCalls(metric.getModelCalls());
            detail.setToolCalls(metric.getToolCalls());
            detail.setToolFailures(metric.getToolFailures());
            detail.setOutputGuarded(metric.getOutputGuarded());
            detail.setStartedAt(metric.getCreatedAt());
        }
        if (metric != null && metric.getUserId() != null) {
            List<Map<String, Object>> found = searchMapper.searchUsers(String.valueOf(metric.getUserId()), 1);
            if (!found.isEmpty()) detail.setUsername(str(found.get(0).get("username")));
        }

        List<AiLlmCallVO> llmCalls = llmCalls(traceId, spans);
        List<AiToolCallVO> toolCalls = toolCalls(traceId);
        detail.setLlmCalls(llmCalls);
        detail.setToolCallsDetail(toolCalls);

        if (spans.isEmpty()) {
            detail.setTraceNote("该请求没有 Trace 明细：可能是采集关闭（ai.trace.enabled=false）、"
                    + "追踪落库失败，或产生于本功能上线之前。汇总指标仍来自 tb_ai_task_metric。");
            detail.setSpans(List.of());
            detail.setConsistency(consistency(metric, null, null));
        } else {
            detail.setSpans(buildTree(spans, llmCalls, toolCalls));
            AiTraceSpan root = spans.stream()
                    .filter(s -> AiTraceSpan.TYPE_REQUEST.equals(s.getSpanType()))
                    .findFirst().orElse(null);
            if (root != null) {
                detail.setStartedAt(root.getStartedAt());
                detail.setEndedAt(root.getEndedAt());
                detail.setRequestSummary(root.getRequestSummary());
                if (detail.getChatId() == null) detail.setChatId(root.getChatId());
                if (detail.getUserId() == null) detail.setUserId(root.getUserId());
            }
            // 提示词版本与动态块状态在 CONTEXT Span 上（根 Span 只有摘要），
            // 取错了会让"上下文"页只显示一个空对象。
            AiTraceSpan contextSpan = contextSpan(spans);
            detail.setContext(parseJson(contextSpan != null
                    ? contextSpan.getAttributesJson()
                    : root == null ? null : root.getAttributesJson()));
            if (detail.getLatencyMs() == null && root != null) detail.setLatencyMs(root.getDurationMs());
            detail.setConsistency(consistency(metric, spans, llmCalls));
        }
        if (detail.getStartedAt() != null && detail.getEndedAt() != null) {
            detail.setLatencyMs(detail.getLatencyMs() != null ? detail.getLatencyMs()
                    : Duration.between(detail.getStartedAt(), detail.getEndedAt()).toMillis());
        }
        long input = detail.getInputTokens() == null ? 0 : detail.getInputTokens();
        long output = detail.getOutputTokens() == null ? 0 : detail.getOutputTokens();
        detail.setTotalTokens(input + output);
        List<AiCostCalculator.TierUsage> usage = new ArrayList<>();
        for (AiLlmCallVO call : llmCalls) {
            usage.add(new AiCostCalculator.TierUsage(
                    call.getTokenSource() != null && call.getInputTokens() != null
                            ? costs.tierIndexFor(call.getInputTokens()) : -1,
                    1, call.getInputTokens(), call.getOutputTokens(),
                    "PROVIDER".equals(call.getTokenSource())));
        }
        detail.setCost(costs.estimateGroups(usage));
        return detail;
    }

    /** 单个 Span 的明细（前端点击执行树节点时按需加载）。 */
    public Map<String, Object> spanDetail(String traceId, String spanId) {
        if (traceId == null || spanId == null) throw notFound();
        AiTraceSpan span = traceMapper.findSpans(traceId).stream()
                .filter(s -> spanId.equals(s.getSpanId())).findFirst().orElse(null);
        if (span == null) throw notFound();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("span", span);
        result.put("attributes", parseJson(span.getAttributesJson()));
        if (AiTraceSpan.TYPE_MODEL.equals(span.getSpanType())) {
            AiTraceLlmCall call = traceMapper.findLlmCallBySpan(spanId);
            if (call != null) result.put("llmCall", toLlmVO(call, span, spansContext(traceId)));
        }
        if (AiTraceSpan.TYPE_TOOL.equals(span.getSpanType())) {
            AiTraceToolCall call = traceMapper.findToolCallBySpan(spanId);
            if (call != null) result.put("toolCall", toToolVO(call));
        }
        return result;
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 批量组装模型调用明细。
     *
     * <p>Span 列表由调用方传入，避免详情页把同一份执行树重复查两遍；
     * CONTEXT Span 也只取一次，供所有调用共享（它是整次请求的属性，不是单次调用的）。
     */
    private List<AiLlmCallVO> llmCalls(String traceId, List<AiTraceSpan> spans) {
        Map<String, AiTraceSpan> bySpan = new HashMap<>();
        for (AiTraceSpan span : spans) bySpan.put(span.getSpanId(), span);
        AiTraceSpan context = contextSpan(spans);
        List<AiLlmCallVO> rows = new ArrayList<>();
        for (AiTraceLlmCall call : traceMapper.findLlmCalls(traceId)) {
            rows.add(toLlmVO(call, bySpan.get(call.getSpanId()), context));
        }
        return rows;
    }

    /** 单 Span 明细入口用：只查一次执行树，取出 CONTEXT 节点。 */
    private AiTraceSpan spansContext(String traceId) {
        return contextSpan(traceMapper.findSpans(traceId));
    }

    private static AiTraceSpan contextSpan(List<AiTraceSpan> spans) {
        return spans.stream()
                .filter(s -> AiTraceSpan.TYPE_CONTEXT.equals(s.getSpanType()))
                .findFirst().orElse(null);
    }

    private List<AiToolCallVO> toolCalls(String traceId) {
        List<AiToolCallVO> rows = new ArrayList<>();
        for (AiTraceToolCall call : traceMapper.findToolCalls(traceId)) {
            rows.add(toToolVO(call));
        }
        return rows;
    }

    private AiLlmCallVO toLlmVO(AiTraceLlmCall call, AiTraceSpan span, AiTraceSpan context) {
        AiLlmCallVO vo = new AiLlmCallVO();
        vo.setSpanId(call.getSpanId());
        vo.setCallIndex(call.getCallIndex());
        vo.setMessages(parseJson(call.getMessagesJson()));
        vo.setSystemMessage(call.getSystemMessage());
        vo.setPromptHash(call.getPromptHash());
        vo.setTools(parseJson(call.getToolsJson()));
        vo.setOutputText(call.getOutputText());
        vo.setToolRequests(parseJson(call.getToolRequestsJson()));
        vo.setFinishReason(call.getFinishReason());
        vo.setSnapshotState(call.getSnapshotState());
        vo.setSnapshotNote(call.getSnapshotNote());
        vo.setCreatedAt(call.getCreatedAt());
        if (span != null) {
            vo.setModel(span.getModel());
            vo.setInputTokens(span.getInputTokens());
            vo.setOutputTokens(span.getOutputTokens());
            vo.setTokenSource(span.getTokenSource());
            vo.setDurationMs(span.getDurationMs());
            vo.setStatus(span.getStatus());
            vo.setErrorCategory(span.getErrorCategory());
            vo.setErrorMessage(span.getErrorMessage());
        }
        if (call.getPromptHash() != null) {
            vo.setPromptVersion(traceMapper.findPromptVersion(call.getPromptHash()));
            vo.setBasePrompt(traceMapper.findBasePrompt(call.getPromptHash()));
        }
        // 实际生效的完整提示词在 CONTEXT Span 上（含动态块），这里补进每次调用详情，
        // 便于单看某次调用时也能确认"当时到底给了模型什么"。
        if (context != null) {
            Map<String, Object> attributes = parseJsonMap(context.getAttributesJson());
            if (attributes != null) {
                vo.setEffectivePrompt((String) attributes.get("effectivePrompt"));
                vo.setBlocks(attributes.get("blocks"));
            }
        }
        return vo;
    }

    private AiToolCallVO toToolVO(AiTraceToolCall call) {
        AiToolCallVO vo = new AiToolCallVO();
        vo.setSpanId(call.getSpanId());
        vo.setToolName(call.getToolName());
        vo.setModelToolCallId(call.getModelToolCallId());
        vo.setServerToolCallId(call.getServerToolCallId());
        vo.setRawArguments(call.getRawArguments());
        vo.setParsedArguments(parseJson(call.getParsedArguments()));
        vo.setStatus(call.getStatus());
        vo.setResultCode(call.getResultCode());
        vo.setResultTotal(call.getResultTotal());
        vo.setResultSummary(call.getResultSummary());
        vo.setResultPayload(parseJson(call.getResultPayload()));
        vo.setErrorCategory(call.getErrorCategory());
        vo.setDurationMs(call.getDurationMs());
        vo.setSnapshotState(call.getSnapshotState());
        vo.setSnapshotNote(call.getSnapshotNote());
        vo.setCreatedAt(call.getCreatedAt());
        return vo;
    }

    /** 按真实 parentSpanId 组树；父节点缺失时挂到根上，避免节点"消失"。 */
    private List<AiSpanVO> buildTree(List<AiTraceSpan> spans, List<AiLlmCallVO> llmCalls,
                                     List<AiToolCallVO> toolCalls) {
        Map<String, AiSpanVO> nodes = new LinkedHashMap<>();
        Set<String> modelSpans = new HashSet<>();
        Set<String> toolSpans = new HashSet<>();
        for (AiLlmCallVO call : llmCalls) modelSpans.add(call.getSpanId());
        for (AiToolCallVO call : toolCalls) toolSpans.add(call.getSpanId());

        for (AiTraceSpan span : spans) {
            AiSpanVO node = new AiSpanVO();
            node.setSpanId(span.getSpanId());
            node.setParentSpanId(span.getParentSpanId());
            node.setSpanType(span.getSpanType());
            node.setName(span.getName());
            node.setStatus(span.getStatus());
            node.setStartedAt(span.getStartedAt());
            node.setEndedAt(span.getEndedAt());
            node.setDurationMs(span.getDurationMs());
            node.setModel(span.getModel());
            node.setInputTokens(span.getInputTokens());
            node.setOutputTokens(span.getOutputTokens());
            node.setTokenSource(span.getTokenSource());
            node.setErrorCategory(span.getErrorCategory());
            node.setErrorMessage(span.getErrorMessage());
            node.setAttributes(parseJson(span.getAttributesJson()));
            node.setHasDetail(modelSpans.contains(span.getSpanId()) || toolSpans.contains(span.getSpanId()));
            nodes.put(span.getSpanId(), node);
        }

        List<AiSpanVO> roots = new ArrayList<>();
        for (AiSpanVO node : nodes.values()) {
            String parentId = node.getParentSpanId();
            AiSpanVO parent = parentId == null ? null : nodes.get(parentId);
            if (parent == null || parent == node) roots.add(node);
            else parent.getChildren().add(node);
        }
        // 同级按开始时间排序：并行调用时顺序才是真实时序。
        Comparator<AiSpanVO> byStart = Comparator.comparing(AiSpanVO::getStartedAt,
                Comparator.nullsLast(Comparator.naturalOrder()));
        roots.sort(byStart);
        nodes.values().forEach(node -> node.getChildren().sort(byStart));
        return roots;
    }

    /**
     * 汇总与明细的一致性核对。
     *
     * <p>两套口径本就不同（任务表按请求汇总，Span 按调用累加），差异必须被<b>说明</b>
     * 而不是被抹平：{@code consistent=false} 时界面提示"两者不一致，请以明细为准排查"。
     */
    private Map<String, Object> consistency(AiTaskMetric metric, List<AiTraceSpan> spans,
                                            List<AiLlmCallVO> llmCalls) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (metric == null) {
            result.put("comparable", false);
            result.put("note", "缺少任务级汇总记录，只展示 Trace 明细");
            return result;
        }
        if (spans == null) {
            result.put("comparable", false);
            result.put("note", "没有 Trace 明细，无法核对");
            return result;
        }
        long spanInput = 0, spanOutput = 0;
        for (AiTraceSpan span : spans) {
            if (!AiTraceSpan.TYPE_MODEL.equals(span.getSpanType())) continue;
            if (span.getInputTokens() != null) spanInput += span.getInputTokens();
            if (span.getOutputTokens() != null) spanOutput += span.getOutputTokens();
        }
        long metricInput = metric.getInputTokens() == null ? 0 : metric.getInputTokens();
        long metricOutput = metric.getOutputTokens() == null ? 0 : metric.getOutputTokens();
        result.put("comparable", true);
        result.put("metricInputTokens", metricInput);
        result.put("metricOutputTokens", metricOutput);
        result.put("spanInputTokens", spanInput);
        result.put("spanOutputTokens", spanOutput);
        result.put("llmCallCount", llmCalls == null ? 0 : llmCalls.size());
        result.put("modelCallCount", metric.getModelCalls());
        boolean consistent = metricInput == spanInput && metricOutput == spanOutput;
        result.put("consistent", consistent);
        result.put("note", consistent ? "任务汇总与逐次调用明细一致"
                : "任务汇总与逐次调用明细不一致：以明细为准排查，差异通常来自采集关闭或部分调用失败");
        return result;
    }

    private Range normalize(LocalDateTime from, LocalDateTime to) {
        LocalDateTime end = to == null ? LocalDateTime.now() : to;
        LocalDateTime start = from == null ? end.minusDays(7) : from;
        if (!start.isBefore(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "起始时间必须早于结束时间");
        }
        if (Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "查询区间不能超过 " + MAX_RANGE.toDays() + " 天");
        }
        return new Range(start, end);
    }

    private static String format(LocalDateTime value) {
        return value == null ? null : value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static BigDecimal rate(long numerator, long denominator) {
        if (denominator <= 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private static long num(Object value) {
        if (value == null) return 0;
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Long nullableNum(Object value) {
        return value == null ? null : num(value);
    }

    private static long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        return num(value);
    }

    /** H2 会把未加引号的列名/别名转成大写，生产 MySQL 通常保留原大小写。 */
    private static Object mapValue(Map<String, Object> row, String camelCase, String upperCase) {
        return row.containsKey(camelCase) ? row.get(camelCase) : row.get(upperCase);
    }

    /** 与 {@link #mapValue} 相同，并兼容 H2 的 SNAKE_CASE 列名。 */
    private static Object value(Map<String, Object> row, String camelCase) {
        Object direct = mapValue(row, camelCase, camelCase.toUpperCase(Locale.ROOT));
        if (direct != null) return direct;
        // 转成真正的小写 snake_case：MySQL 会保留 token_source 的小写形式，
        // 不能生成 token_Source 后只靠大写兜底（H2 恰好会把别名转成大写，曾掩盖此问题）。
        String snake = camelCase.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase(Locale.ROOT);
        return mapValue(row, snake, snake.toUpperCase(Locale.ROOT));
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static Object parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return AgentJson.MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            // 解析失败时返回原文，便于人工排查；不静默丢弃。
            return json;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonMap(String json) {
        Object value = parseJson(json);
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Trace 不存在");
    }

    private record Range(LocalDateTime from, LocalDateTime to) {
    }
}
