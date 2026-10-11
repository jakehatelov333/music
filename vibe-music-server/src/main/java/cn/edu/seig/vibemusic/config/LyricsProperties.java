package cn.edu.seig.vibemusic.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 歌词抓取相关配置，对应 application.yml 中的 {@code lyrics} 前缀。
 */
@Data
@Component
@ConfigurationProperties(prefix = "lyrics")
public class LyricsProperties {

    /** 总开关，关闭后所有歌词抓取端点直接返回不可用 */
    private boolean enabled = true;

    /** 可选代理，例如 http://127.0.0.1:8080；留空表示直连 */
    private String proxy = "";

    private Match match = new Match();

    private Providers providers = new Providers();

    private Batch batch = new Batch();

    @Data
    public static class Match {
        /** 匹配得分阈值，低于该值视为无匹配，宁可不写 */
        private double minScore = 0.7;
        /** 时长容差（秒），差值在此范围内加分 */
        private int durationToleranceSeconds = 3;
    }

    @Data
    public static class Batch {
        /** 批量任务线程池大小 */
        private int threadPoolSize = 2;
        /** 任务队列容量 */
        private int queueCapacity = 200;
        /** 每首歌曲之间的间隔，避免触发数据源限流 */
        private long delayMs = 500;
        /** 单次批量任务最多处理多少首 */
        private int maxTaskSize = 1000;
        /** 任务状态在 Redis 中的保留时长（小时） */
        private long taskTtlHours = 24;
    }

    @Data
    public static class Providers {
        private Provider qq = new Provider();
        private Provider lrclib = new Provider();
    }

    @Data
    public static class Provider {
        private boolean enabled = true;
        private String baseUrl;
        /** 部分平台会校验 Referer */
        private String referer;
        /** 大部分接口要求携带可识别的 User-Agent */
        private String userAgent = "VibeMusic/1.0 (https://github.com/jakehatelov333/music)";
        private int connectTimeoutMs = 3000;
        private int readTimeoutMs = 8000;
        /** 遇到 429/5xx/超时时的最大重试次数 */
        private int maxRetries = 2;
    }

}
