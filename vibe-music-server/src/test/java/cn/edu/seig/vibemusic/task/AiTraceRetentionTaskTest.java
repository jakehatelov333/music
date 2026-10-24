package cn.edu.seig.vibemusic.task;

import cn.edu.seig.vibemusic.mapper.AiTraceMapper;
import cn.edu.seig.vibemusic.service.agent.trace.AgentTraceSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Trace 保留期清理的边界行为。
 *
 * <p>这里只验证「删什么、什么时候不删」：保留天数 <= 0 时必须一次都不删
 * （运维可能把清理交给外部任务），清理抛错时不能影响任何业务流程。
 */
class AiTraceRetentionTaskTest {

    private AiTraceMapper mapper;
    private AgentTraceSettings settings;
    private AiTraceRetentionTask task;

    @BeforeEach
    void setUp() {
        mapper = mock(AiTraceMapper.class);
        settings = new AgentTraceSettings();
        task = new AiTraceRetentionTask(mapper, settings);
    }

    @Test
    @DisplayName("保留天数 <= 0 时完全不清理")
    void disabledRetentionDeletesNothing() {
        ReflectionTestUtils.setField(settings, "retentionDays", 0);
        task.purgeExpired();
        ReflectionTestUtils.setField(settings, "retentionDays", -1);
        task.purgeExpired();

        verify(mapper, never()).deleteSpansBefore(any());
        verify(mapper, never()).deleteLlmCallsBefore(any());
        verify(mapper, never()).deleteToolCallsBefore(any());
    }

    @Test
    @DisplayName("按截止时间删三张明细表，且先删明细再删 Span")
    void purgesExpiredRowsInDependencyOrder() {
        ReflectionTestUtils.setField(settings, "retentionDays", 30);
        when(mapper.deleteToolCallsBefore(any(LocalDateTime.class))).thenReturn(2);
        when(mapper.deleteLlmCallsBefore(any(LocalDateTime.class))).thenReturn(3);
        when(mapper.deleteSpansBefore(any(LocalDateTime.class))).thenReturn(5);

        task.purgeExpired();

        verify(mapper, times(1)).deleteToolCallsBefore(any(LocalDateTime.class));
        verify(mapper, times(1)).deleteLlmCallsBefore(any(LocalDateTime.class));
        verify(mapper, times(1)).deleteSpansBefore(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("清理失败只记日志，不抛出给调度器")
    void failureDoesNotPropagate() {
        ReflectionTestUtils.setField(settings, "retentionDays", 30);
        when(mapper.deleteToolCallsBefore(any(LocalDateTime.class)))
                .thenThrow(new IllegalStateException("db down"));

        task.purgeExpired();

        // 失败后不应继续删后面的表，但绝不能把异常抛出去。
        verify(mapper, never()).deleteSpansBefore(any());
    }
}
