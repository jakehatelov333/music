package cn.edu.seig.vibemusic.service.lyrics;

import cn.edu.seig.vibemusic.config.LyricsProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.Comparator;
import java.util.List;

/**
 * 【手动探针】用真实数据源实跑一首歌的歌手维度得分，用于验证「主艺人名 vs 完整展示串」的量化差异。
 *
 * <p>默认不执行（需要联网）。运行方式：
 * <pre>mvn -o test -Dtest=LyricsMatchScoreProbe -Dlyrics.probe=true -DfailIfNoTests=false</pre>
 *
 * <p>背景：《牵丝戏》的 {@code tb_song.artist_id} 是主艺人「银临」，展示串是「银临 / Aki阿杰」。
 * {@code LyricsMatcher} 的归一化会清掉 {@code /} 与空格，而 QQ 音乐把多位歌手拼成
 * 「银临/Aki阿杰」返回 —— 于是传主艺人名只能拿到「包含关系」（+0.15），传完整展示串才可能相等（+0.3）。
 * 本探针把两种传参的实际得分打印出来，确认阈值（0.7）余量。
 */
@SpringJUnitConfig(LyricsMatchScoreProbe.Config.class)
@EnabledIfSystemProperty(named = "lyrics.probe", matches = "true")
class LyricsMatchScoreProbe {

    /** 与本地库 {@code tb_song.id=622} 一致：歌名/专辑/时长（秒）。 */
    private static final String TRACK = "牵丝戏";
    private static final String ALBUM = "牵丝戏";
    private static final Double DURATION = 239.3;

    private static final List<String> ARTIST_NAMES = List.of("银临", "银临 / Aki阿杰", "Aki阿杰");

    @Autowired
    private List<LyricsProvider> providers;

    @Autowired
    private LyricsMatcher matcher;

    @Autowired
    private LyricsProperties properties;

    @Test
    void reportArtistDimensionScores() {
        double realMinScore = properties.getMatch().getMinScore();
        System.out.println("========== 歌词歌手维度得分实跑（阈值 " + realMinScore + "） ==========");

        for (String artist : ARTIST_NAMES) {
            LyricsProvider.Query query = new LyricsProvider.Query(TRACK, artist, ALBUM, DURATION);
            System.out.println();
            System.out.println("--- 检索歌手名 = [" + artist + "] ---");

            for (LyricsProvider provider : orderedProviders()) {
                if (!provider.enabled()) {
                    System.out.println("  " + provider.source() + "：未启用");
                    continue;
                }

                // 1) 关掉阈值门槛，观察数据源返回的最佳候选「真实得分」（不设门槛才能看到低于阈值的情况）
                properties.getMatch().setMinScore(0.0);
                List<LyricsCandidate> raw;
                try {
                    raw = provider.search(query);
                } catch (Exception e) {
                    System.out.println("  " + provider.source() + "：请求异常 " + e);
                    properties.getMatch().setMinScore(realMinScore);
                    continue;
                }

                // 2) 恢复真实阈值，观察真实链路上这个数据源是否接受
                properties.getMatch().setMinScore(realMinScore);
                List<LyricsCandidate> accepted = provider.search(query);

                if (raw.isEmpty()) {
                    System.out.println("  " + provider.source() + "：无候选");
                    continue;
                }
                for (LyricsCandidate candidate : raw) {
                    double score = matcher.score(candidate, query);
                    System.out.printf("  %-9s 歌名=%s | 歌手=%s | 时长=%s | 得分=%.3f | 阈值判定=%s%n",
                            provider.source(), candidate.trackName(), candidate.artistName(),
                            candidate.durationSeconds(), score,
                            score >= realMinScore ? "通过" : "丢弃");
                }
                System.out.println("        真实阈值下该源返回候选数=" + accepted.size()
                        + (accepted.isEmpty() ? "（本首歌不会被这个源接受）" : ""));
            }
        }
        System.out.println();
        System.out.println("========== 实跑结束 ==========");
    }

    private List<LyricsProvider> orderedProviders() {
        return providers.stream().sorted(Comparator.comparingInt(LyricsProvider::order)).toList();
    }

    @Configuration
    static class Config {
        @Bean
        LyricsProperties properties() {
            LyricsProperties properties = new LyricsProperties();
            // 与 application.yml 的 lyrics.providers 保持一致（纯 Java 配置不会读 yml）
            properties.getProviders().getQq().setBaseUrl("https://c.y.qq.com");
            properties.getProviders().getQq().setReferer("https://y.qq.com/portal/player.html");
            properties.getProviders().getLrclib().setBaseUrl("https://lrclib.net");
            return properties;
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        LyricsHttpClient http() {
            return new LyricsHttpClient();
        }

        @Bean
        LyricsMatcher matcher() {
            return new LyricsMatcher();
        }

        @Bean
        QqMusicCatalog qqMusicCatalog() {
            return new QqMusicCatalog();
        }

        @Bean
        QqMusicProvider qqMusicProvider() {
            return new QqMusicProvider();
        }

        @Bean
        LrcLibProvider lrcLibProvider() {
            return new LrcLibProvider();
        }
    }
}
