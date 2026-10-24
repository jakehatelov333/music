package cn.edu.seig.vibemusic.task;

import cn.edu.seig.vibemusic.mapper.AiTraceMapper;
import cn.edu.seig.vibemusic.service.agent.trace.AgentTraceSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Trace 保留期清理。
 *
 * <p><b>为什么需要它：</b>Trace 保存的是完整提示词、模型输入与工具返回，体积远大于
 * 指标表。没有保留策略的话，几个月后表会大到影响查询，也会让隐私数据无限期留存。
 *
 * <p><b>只删过期数据，不做批量清空：</b>每次删除都带 {@code started_at < 截止时间} 条件，
 * 且分表按各自的时间列判断；{@code retention-days <= 0} 表示不清理（由运维自行管理）。
 *
 * <p><b>与指标表的关系：</b>只清理 Trace 明细，不动 {@code tb_ai_task_metric} ——
 * 指标是轻量的汇总，长期保留仍有价值；Trace 过期后详情页会明确提示
 * "该请求没有 Trace 明细"，而不是显示一棵空树。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiTraceRetentionTask {

    private final AiTraceMapper traceMapper;
    private final AgentTraceSettings settings;

    @Scheduled(initialDelayString = "${ai.trace.cleanup-initial-delay-ms:300000}",
            fixedDelayString = "${ai.trace.cleanup-interval-ms:86400000}")
    public void purgeExpired() {
        int days = settings.retentionDays();
        if (days <= 0) return;
        LocalDateTime before = LocalDateTime.now().minusDays(days);
        try {
            int toolCalls = traceMapper.deleteToolCallsBefore(before);
            int llmCalls = traceMapper.deleteLlmCallsBefore(before);
            int spans = traceMapper.deleteSpansBefore(before);
            if (toolCalls + llmCalls + spans > 0) {
                log.info("清理过期 AI Trace：spans={} llmCalls={} toolCalls={}，保留 {} 天",
                        spans, llmCalls, toolCalls, days);
            }
        } catch (RuntimeException e) {
            // 清理失败不影响业务，下次定时任务会重试。
            log.warn("清理过期 AI Trace 失败，类型={}", e.getClass().getSimpleName());
        }
    }
}
