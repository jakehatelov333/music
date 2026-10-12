package cn.edu.seig.vibemusic.service.agent;

import cn.edu.seig.vibemusic.mapper.AiTaskMetricMapper;
import cn.edu.seig.vibemusic.model.entity.AiTaskMetric;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 把 {@link AgentMetrics} 落到 {@code tb_ai_task_metric}。
 *
 * <p>刻意吞掉落库异常：指标是旁路能力，绝不能因为它失败而让用户拿不到回复。
 * 但会打 warn 日志，便于发现问题。
 *
 * <p>落库内容不含用户消息、模型回复、API Key 或任何认证凭据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentMetricsRecorder {

    private final AiTaskMetricMapper metrics;

    public void record(AgentMetrics source) {
        if (source == null || !source.finished()) return;
        try {
            AiTaskMetric row = new AiTaskMetric();
            row.setTaskId(source.taskId());
            row.setUserId(source.userId());
            row.setChatId(source.chatId());
            row.setModel(source.model());
            row.setModelCalls(source.modelCalls());
            row.setToolCalls(source.toolCalls());
            row.setToolFailures(source.toolFailures());
            row.setInputTokens(source.inputTokens());
            row.setOutputTokens(source.outputTokens());
            row.setTokenSource(source.tokenSource());
            row.setResultCount(source.resultCount());
            row.setSuccess(source.success());
            row.setFailureCategory(source.failureCategory());
            row.setFailureMessage(truncate(source.failureMessage()));
            row.setOutputGuarded(source.outputGuarded());
            row.setLatencyMs(source.latencyMillis());
            row.setPlaylistId(source.playlistId());
            row.setCreatedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(source.startedMillis()),
                    ZoneId.systemDefault()));
            metrics.insert(row);
        } catch (RuntimeException e) {
            log.warn("Agent 指标落库失败 taskId={} type={}", source.taskId(), e.getClass().getSimpleName());
        }
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    /** 当前用户自己的最近任务指标，用于演示时现场查看真实耗时与 Token。 */
    public java.util.List<AiTaskMetric> recentForUser(Long userId, int limit) {
        return metrics.findRecentByUser(userId, Math.min(Math.max(limit, 1), 100));
    }
}
