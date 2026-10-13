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
        /**
         * 每首歌最多尝试几个「歌手名候选」。候选顺序为：主艺人名 → 完整展示串 → 各合作歌手名。
         *
         * <p>为什么需要回退：歌词源的歌手维度得分区分「完全相等（+0.3）」与「包含（+0.15）」，
         * 而 {@code LyricsMatcher} 的归一化会清掉 {@code /} 与空格。因此对合作歌曲，
         * 传主艺人名只能拿到包含关系，传完整展示串才可能相等。多试一个候选即可拿回分差，
         * 但必须限次，否则批量补全的请求量会成倍上涨。
         */
        private int maxArtistAttempts = 2;
        /**
         * 是否允许把「各合作歌手名」也作为候选（回退链的第 3 级）。默认关闭：
         * 主艺人名 + 完整展示串已覆盖绝大多数情况，逐位合作歌手再各试一次代价偏高。
         */
        private boolean allowCollaboratorAttempts = false;
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
