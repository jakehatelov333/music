package cn.edu.seig.vibemusic.config;

import cn.edu.seig.vibemusic.config.LyricsProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 歌词批量补全任务的线程池。
 * <p>
 * 并发刻意保持很低（默认 2），因为数据源对请求频率敏感，
 * 真正的节流由每首歌曲之间的间隔（{@code lyrics.batch.delay-ms}）保证。
 */
@Configuration
public class LyricsAsyncConfig {

    @Bean("lyricsTaskExecutor")
    public ThreadPoolTaskExecutor lyricsTaskExecutor(LyricsProperties properties) {
        LyricsProperties.Batch batch = properties.getBatch();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, batch.getThreadPoolSize()));
        executor.setMaxPoolSize(Math.max(1, batch.getThreadPoolSize()));
        executor.setQueueCapacity(Math.max(1, batch.getQueueCapacity()));
        executor.setThreadNamePrefix("lyrics-task-");
        // 队列满时由调用线程执行，保证任务不丢失
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

}
