package cn.edu.seig.vibemusic.task;

import cn.edu.seig.vibemusic.mapper.AgentPendingActionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 把超时未确认的待确认任务置为 EXPIRED。
 *
 * <p>过期判断在读取与确认路径上也会做（惰性过期），本定时任务只是让
 * 「长期没人管的待确认任务」也能在数据库里落到终态，方便运维与统计。
 * 过期任务不会被执行，也无法被确认。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentPendingActionExpiryTask {

    private final AgentPendingActionMapper actions;

    @Scheduled(
            initialDelayString = "${ai.pending-action-sweep-initial-delay-ms:60000}",
            fixedDelayString = "${ai.pending-action-sweep-interval-ms:300000}"
    )
    public void expireStaleActions() {
        int expired = actions.expireAll(LocalDateTime.now());
        if (expired > 0) log.info("待确认任务过期清理完成，数量={}", expired);
    }
}
