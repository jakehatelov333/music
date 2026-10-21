package cn.edu.seig.vibemusic.agent;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.mapper.*;
import cn.edu.seig.vibemusic.model.agent.AgentContext;
import cn.edu.seig.vibemusic.model.agent.AgentEvent;
import cn.edu.seig.vibemusic.security.PlaylistAccessService;
import cn.edu.seig.vibemusic.service.IPlaylistService;
import cn.edu.seig.vibemusic.service.MediaCleanupService;
import cn.edu.seig.vibemusic.service.MinioService;
import cn.edu.seig.vibemusic.service.PersonalPlaylistService;
import cn.edu.seig.vibemusic.service.ArtistNameParser;
import cn.edu.seig.vibemusic.service.SongArtistService;
import cn.edu.seig.vibemusic.service.agent.AgentMetrics;
import cn.edu.seig.vibemusic.service.agent.AgentSongQueryService;
import cn.edu.seig.vibemusic.service.agent.PendingActionService;
import cn.edu.seig.vibemusic.service.agent.PlaylistSelectionHistoryService;
import cn.edu.seig.vibemusic.service.impl.PlaylistServiceImpl;
import cn.edu.seig.vibemusic.tool.MusicTools;
import cn.edu.seig.vibemusic.tool.PlaylistActionTools;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.mock;

/**
 * Agent 测试的共享基座：真实 MySQL 模式内存库 + 真实 mapper SQL + 真实业务服务。
 *
 * <p>刻意不使用 mock 替身覆盖业务层，因为要验证的正是“确认前不写库”“幂等不重复建歌单”
 * “越权被拒绝”这类<b>数据库层面</b>的事实。模型是唯一被替换的部件。
 */
@SpringJUnitConfig(AbstractAgentTest.Config.class)
public abstract class AbstractAgentTest {

    @Autowired protected PendingActionService pendingActions;
    @Autowired protected PersonalPlaylistService personalPlaylists;
    @Autowired protected AgentSongQueryService songQuery;
    @Autowired protected AgentSongMapper agentSongs;
    @Autowired protected PlaylistMapper playlists;
    @Autowired protected AgentPendingActionMapper actionMapper;
    @Autowired protected AiTaskMetricMapper metricMapper;
    @Autowired protected PlaylistSelectionHistoryService selectionHistory;
    @Autowired protected SongArtistService songArtists;
    @Autowired protected ArtistNameParser artistNameParser;
    @Autowired protected MusicTools musicTools;
    @Autowired protected cn.edu.seig.vibemusic.service.agent.UserMusicProfileService profileService;
    @Autowired protected cn.edu.seig.vibemusic.service.agent.UserMemoryPreferenceService memoryPreferenceService;
    @Autowired protected AgentMemoryMapper memoryMapper;
    @Autowired protected DataSource dataSource;

    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        jdbc = new JdbcTemplate(dataSource);
        createSchema();
        resetData();
    }

    /** 清空业务数据并写入固定夹具；评测集会在每个用例之间重复调用。 */
    protected void resetData() {
        jdbc.update("DELETE FROM tb_play_history");
        jdbc.update("DELETE FROM tb_user_memory_preference");
        jdbc.update("DELETE FROM tb_user_ai_memory_setting");
        jdbc.update("DELETE FROM tb_user_music_profile");
        jdbc.update("DELETE FROM ai_chat_state");
        jdbc.update("DELETE FROM chat_messages");
        jdbc.update("DELETE FROM tb_ai_task_metric");
        jdbc.update("DELETE FROM tb_agent_pending_action");
        jdbc.update("DELETE FROM tb_playlist_create_request");
        jdbc.update("DELETE FROM tb_user_favorite");
        jdbc.update("DELETE FROM tb_comment");
        jdbc.update("DELETE FROM tb_playlist_binding");
        jdbc.update("DELETE FROM tb_playlist");
        jdbc.update("DELETE FROM tb_song_artist");
        jdbc.update("DELETE FROM tb_song");
        jdbc.update("DELETE FROM tb_album");
        jdbc.update("DELETE FROM tb_artist");
        jdbc.update("DELETE FROM tb_user");
        jdbc.update("INSERT INTO tb_user(id, username) VALUES (1,'alice'), (2,'bob')");
        jdbc.update("INSERT INTO tb_artist(id, name, area, gender, introduction) "
                + "VALUES (1,'测试歌手A','内地',0,'华语流行男歌手'), (2,'测试歌手B','中国香港',1,'粤语女歌手')");
        jdbc.update("INSERT INTO tb_album(id, name, artist_id) VALUES (1,'测试专辑A',1)");
        jdbc.update("INSERT INTO tb_song(id,name,album,album_id,artist_id,style,duration,cover_url,audio_url,artist_names) VALUES "
                + "(10,'夜色温柔','测试专辑A',1,1,'流行','03:20','http://minio/10.jpg','http://minio/10.mp3','测试歌手A'),"
                + "(11,'晚风轻语','测试专辑A',1,1,'流行','04:05','http://minio/11.jpg','http://minio/11.mp3','测试歌手A'),"
                + "(12,'星河滚烫','测试专辑A',1,1,'摇滚','02:50','http://minio/12.jpg','http://minio/12.mp3','测试歌手A'),"
                + "(13,'雨落长街',NULL,NULL,2,'民谣','03:41','http://minio/13.jpg','http://minio/13.mp3','测试歌手B'),"
                + "(14,'旧巷口的猫',NULL,NULL,2,'粤语','03:12','http://minio/14.jpg','http://minio/14.mp3','测试歌手B')");
        // 关系表与 tb_song.artist_names 必须同时维护：前者是权威关系，后者是派生展示串。
        jdbc.update("INSERT INTO tb_song_artist(song_id, artist_id, display_order) VALUES "
                + "(10,1,1),(11,1,1),(12,1,1),(13,2,1),(14,2,1)");
        ThreadLocalUtil.remove();
    }

    @AfterEach
    void clearIdentity() {
        ThreadLocalUtil.remove();
    }

    private void createSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_user(id BIGINT PRIMARY KEY, username VARCHAR(255), user_avatar VARCHAR(255))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_artist(id BIGINT PRIMARY KEY, name VARCHAR(255), gender INT, "
                + "avatar VARCHAR(255), birth DATE, area VARCHAR(64), introduction VARCHAR(1000), "
                + "status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', merged_into_artist_id BIGINT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_album(id BIGINT PRIMARY KEY, name VARCHAR(255), artist_id BIGINT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_song(id BIGINT PRIMARY KEY, name VARCHAR(255), album VARCHAR(255), "
                + "album_id BIGINT, artist_id BIGINT, artist_names VARCHAR(512), style VARCHAR(64), duration VARCHAR(20), "
                + "cover_url VARCHAR(255), audio_url VARCHAR(255), release_time DATE, disc_number INT, track_number INT, "
                + "lyric CLOB, lyric_source VARCHAR(32), lyric_status INT)");
        // 歌曲-演唱歌手关系（权威数据源）：多歌手改造后，歌手归属只能查这张表。
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_song_artist(song_id BIGINT NOT NULL, artist_id BIGINT NOT NULL, "
                + "display_order INT NOT NULL DEFAULT 1, "
                + "PRIMARY KEY(song_id, artist_id), "
                + "FOREIGN KEY(song_id) REFERENCES tb_song(id) ON DELETE CASCADE, "
                + "FOREIGN KEY(artist_id) REFERENCES tb_artist(id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_playlist(id BIGINT AUTO_INCREMENT PRIMARY KEY, title VARCHAR(255), "
                + "cover_url VARCHAR(255), introduction VARCHAR(2000), style VARCHAR(255), owner_user_id BIGINT, "
                + "is_public BOOLEAN DEFAULT TRUE, status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED', "
                + "cover_is_custom BOOLEAN NOT NULL DEFAULT FALSE, "
                + "FOREIGN KEY(owner_user_id) REFERENCES tb_user(id) ON DELETE CASCADE)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_playlist_binding(playlist_id BIGINT, song_id BIGINT, "
                + "PRIMARY KEY(playlist_id,song_id), "
                + "FOREIGN KEY(playlist_id) REFERENCES tb_playlist(id) ON DELETE CASCADE, "
                + "FOREIGN KEY(song_id) REFERENCES tb_song(id) ON DELETE CASCADE)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_comment(id BIGINT PRIMARY KEY, playlist_id BIGINT, type INT, "
                + "content VARCHAR(255), create_time TIMESTAMP, like_count BIGINT, user_id BIGINT, "
                + "FOREIGN KEY(playlist_id) REFERENCES tb_playlist(id) ON DELETE CASCADE)");
        // song_id 是画像聚合的必需列（真实表 tb_user_favorite 有 type=0 时写 song_id）。
        // 刻意不加 song_id 外键：resetData 的删除顺序已保证先删收藏再删歌曲，加约束只会徒增顺序耦合。
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_user_favorite(id BIGINT PRIMARY KEY, user_id BIGINT, "
                + "playlist_id BIGINT, song_id BIGINT, type INT, create_time TIMESTAMP, "
                + "FOREIGN KEY(playlist_id) REFERENCES tb_playlist(id) ON DELETE CASCADE)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_playlist_create_request(user_id BIGINT, request_key VARCHAR(64), "
                + "request_hash CHAR(64), playlist_id BIGINT, PRIMARY KEY(user_id,request_key), "
                + "FOREIGN KEY(user_id) REFERENCES tb_user(id) ON DELETE CASCADE)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_agent_pending_action(id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "task_id VARCHAR(64) NOT NULL, user_id BIGINT NOT NULL, chat_id VARCHAR(64) NOT NULL, "
                + "action_type VARCHAR(32) NOT NULL, draft_json CLOB NOT NULL, preview_json CLOB NOT NULL, "
                + "version INT NOT NULL DEFAULT 1, status VARCHAR(16) NOT NULL, result_playlist_id BIGINT, "
                + "error_code VARCHAR(64), error_message VARCHAR(500), created_at TIMESTAMP NOT NULL, "
                + "updated_at TIMESTAMP NOT NULL, expires_at TIMESTAMP NOT NULL, "
                + "UNIQUE(task_id), FOREIGN KEY(user_id) REFERENCES tb_user(id) ON DELETE CASCADE)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_ai_task_metric(id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "task_id VARCHAR(64) NOT NULL, user_id BIGINT, chat_id VARCHAR(64) NOT NULL, "
                + "model VARCHAR(64) NOT NULL, model_calls INT NOT NULL DEFAULT 0, tool_calls INT NOT NULL DEFAULT 0, "
                + "tool_failures INT NOT NULL DEFAULT 0, input_tokens BIGINT, output_tokens BIGINT, "
                + "token_source VARCHAR(16) NOT NULL DEFAULT 'UNAVAILABLE', result_count INT NOT NULL DEFAULT 0, "
                + "success BOOLEAN NOT NULL DEFAULT FALSE, failure_category VARCHAR(32), failure_message VARCHAR(500), "
                + "output_guarded BOOLEAN NOT NULL DEFAULT FALSE, "
                + "latency_ms BIGINT NOT NULL DEFAULT 0, playlist_id BIGINT, created_at TIMESTAMP NOT NULL, "
                + "UNIQUE(task_id))");
        // 用户音乐画像：显式信号来自收藏与自建歌单，隐式信号来自播放历史（两者分开存）。
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_user_music_profile(user_id BIGINT PRIMARY KEY, "
                + "top_styles CLOB, top_artists CLOB, fav_song_count INT NOT NULL DEFAULT 0, "
                + "fav_playlist_count INT NOT NULL DEFAULT 0, own_playlist_count INT NOT NULL DEFAULT 0, "
                + "own_playlist_song_count INT NOT NULL DEFAULT 0, played_song_count INT NOT NULL DEFAULT 0, "
                + "recent_play_artists CLOB, pref_summary VARCHAR(500), "
                + "summary_source_hash CHAR(64), version BIGINT NOT NULL DEFAULT 0, updated_at TIMESTAMP NOT NULL, "
                + "FOREIGN KEY(user_id) REFERENCES tb_user(id) ON DELETE CASCADE)");
        // 播放历史（sql/phase12_play_history.sql 对齐）：每个 (user, song) 一行。
        // 刻意保留真实库的 ON DELETE CASCADE 语义 —— 「删除歌曲后历史怎么办」正是要验证的点之一。
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_play_history(id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "user_id BIGINT NOT NULL, song_id BIGINT NOT NULL, last_played_at TIMESTAMP NOT NULL, "
                + "play_count INT NOT NULL DEFAULT 1, last_position_seconds INT, last_play_id VARCHAR(64), "
                + "UNIQUE(user_id, song_id), "
                + "FOREIGN KEY(user_id) REFERENCES tb_user(id) ON DELETE CASCADE, "
                + "FOREIGN KEY(song_id) REFERENCES tb_song(id) ON DELETE CASCADE)");
        // 模型窗口 + 租约 + 滚动摘要 + 会话归属
        // （与 sql/phase2_chat_migration.sql、phase5_personalization.sql、phase7_conversations.sql 对齐）。
        jdbc.execute("CREATE TABLE IF NOT EXISTS ai_chat_state(chat_id VARCHAR(255) PRIMARY KEY, "
                + "context_json CLOB, context_version BIGINT NOT NULL DEFAULT 0, active_run_token VARCHAR(36), "
                + "lease_expires_at TIMESTAMP, summary_text CLOB, summary_upto_id BIGINT NOT NULL DEFAULT 0, "
                + "summary_updated_at TIMESTAMP, user_id BIGINT, title VARCHAR(64), created_at TIMESTAMP, "
                + "last_active_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_messages(id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "chat_id VARCHAR(255) NOT NULL, message_type VARCHAR(32) NOT NULL, content CLOB NOT NULL, "
                + "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL)");
        // 用户手动偏好 + 个性化开关（与 sql/phase8_ai_memory_preferences.sql 对齐）。
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_user_memory_preference(id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "user_id BIGINT NOT NULL, category VARCHAR(32) NOT NULL, target_id BIGINT, "
                + "target_value VARCHAR(255) NOT NULL, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, "
                + "UNIQUE(user_id, category, target_value), "
                + "FOREIGN KEY(user_id) REFERENCES tb_user(id) ON DELETE CASCADE)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_user_ai_memory_setting(user_id BIGINT PRIMARY KEY, "
                + "personalization_enabled BOOLEAN NOT NULL DEFAULT TRUE, updated_at TIMESTAMP NOT NULL, "
                + "FOREIGN KEY(user_id) REFERENCES tb_user(id) ON DELETE CASCADE)");
    }

    // ------------------------------------------------------------------ helpers

    protected static void login(Long userId) {
        ThreadLocalUtil.set(Map.of(JwtClaimsConstant.USER_ID, userId, JwtClaimsConstant.ROLE, "ROLE_USER"));
    }

    protected static void logout() {
        ThreadLocalUtil.remove();
    }

    protected int count(String table) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }

    /** 收集事件的测试出口，并顺带断言事件顺序。 */
    protected static final class RecordingSink implements cn.edu.seig.vibemusic.model.agent.AgentEventSink {
        private final List<AgentEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void emit(AgentEvent event) {
            events.add(event);
        }

        public List<AgentEvent> events() {
            return events;
        }

        public List<String> types() {
            return events.stream().map(AgentEvent::type).toList();
        }

        public List<AgentEvent> ofType(String type) {
            return events.stream().filter(e -> type.equals(e.type())).toList();
        }

        public AgentEvent firstOfType(String type) {
            return ofType(type).stream().findFirst().orElse(null);
        }
    }

    /** 构造一个带上下文的只读工具实例。 */
    protected MusicTools readTools(RecordingSink sink, Long userId, String chatId, AgentMetrics metrics) {
        AgentContext context = AgentContext.of(userId, chatId, "task-" + chatId, sink)
                .withMetrics(metrics);
        return new MusicTools(songQuery, artistMapperBean(), playlists, selectionHistory, memoryPreferenceService, context);
    }

    protected PlaylistActionTools actionTools(RecordingSink sink, Long userId, String chatId, AgentMetrics metrics) {
        AgentContext context = AgentContext.of(userId, chatId, "task-" + chatId, sink).withMetrics(metrics);
        return new PlaylistActionTools(pendingActions, context);
    }

    @Autowired protected ArtistMapper artistMapper;
    @Autowired protected AlbumMapper albumMapper;
    @Autowired protected SongMapper songMapper;
    /** 会话元信息（归属、标题、最近活跃时间）与消息窗口都走它。 */
    @Autowired protected AiMapper aiMapper;

    private ArtistMapper artistMapperBean() {
        return artistMapper;
    }

    protected static AtomicInteger counter() {
        return new AtomicInteger();
    }

    @org.springframework.boot.test.context.TestConfiguration
    @org.springframework.transaction.annotation.EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:agent_eval;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
            return source;
        }

        @Bean DataSourceTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean SqlSessionTemplate session(DataSource source) throws Exception {
            MybatisConfiguration config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            config.addMapper(PlaylistMapper.class);
            config.addMapper(PlaylistBindingMapper.class);
            config.addMapper(PlaylistCreateRequestMapper.class);
            config.addMapper(SongMapper.class);
            config.addMapper(AgentSongMapper.class);
            config.addMapper(ArtistMapper.class);
            config.addMapper(AlbumMapper.class);
            config.addMapper(AgentPendingActionMapper.class);
            config.addMapper(AiTaskMetricMapper.class);
            config.addMapper(SearchMapper.class);
            config.addMapper(UserMusicProfileMapper.class);
            config.addMapper(PlayHistoryMapper.class);
            config.addMapper(SongArtistMapper.class);
            config.addMapper(AgentMemoryMapper.class);
            // AiMapper 是 MyBatis-Plus 的 BaseMapper：滚动摘要记忆与会话消息读写都走它。
            config.addMapper(AiMapper.class);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(config);
            factory.setMapperLocations(new ClassPathResource("mapper/PlaylistMapper.xml"));
            MybatisPlusInterceptor pagination = new MybatisPlusInterceptor();
            pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.H2));
            factory.setPlugins(pagination);
            return new SqlSessionTemplate(factory.getObject());
        }

        @Bean PlaylistMapper playlistMapper(SqlSessionTemplate session) { return session.getMapper(PlaylistMapper.class); }
        @Bean PlaylistBindingMapper bindings(SqlSessionTemplate session) { return session.getMapper(PlaylistBindingMapper.class); }
        @Bean PlaylistCreateRequestMapper receipts(SqlSessionTemplate session) { return session.getMapper(PlaylistCreateRequestMapper.class); }
        @Bean SongMapper songs(SqlSessionTemplate session) { return session.getMapper(SongMapper.class); }
        @Bean AgentSongMapper agentSongs(SqlSessionTemplate session) { return session.getMapper(AgentSongMapper.class); }
        @Bean ArtistMapper artists(SqlSessionTemplate session) { return session.getMapper(ArtistMapper.class); }
        @Bean AlbumMapper albumMapper(SqlSessionTemplate session) { return session.getMapper(AlbumMapper.class); }
        @Bean AgentPendingActionMapper actionMapper(SqlSessionTemplate session) { return session.getMapper(AgentPendingActionMapper.class); }
        @Bean AiTaskMetricMapper metricMapper(SqlSessionTemplate session) { return session.getMapper(AiTaskMetricMapper.class); }
        @Bean SearchMapper search(SqlSessionTemplate session) { return session.getMapper(SearchMapper.class); }
        @Bean UserMusicProfileMapper profiles(SqlSessionTemplate session) { return session.getMapper(UserMusicProfileMapper.class); }
        @Bean PlayHistoryMapper playHistoryMapper(SqlSessionTemplate session) { return session.getMapper(PlayHistoryMapper.class); }
        @Bean SongArtistMapper songArtistMapper(SqlSessionTemplate session) { return session.getMapper(SongArtistMapper.class); }
        @Bean AiMapper aiMapper(SqlSessionTemplate session) { return session.getMapper(AiMapper.class); }

        @Bean PlaylistAccessService access(PlaylistMapper mapper) { return new PlaylistAccessService(mapper); }
        @Bean IPlaylistService details() { return new PlaylistServiceImpl(); }
        @Bean UserFavoriteMapper favorites() { return mock(UserFavoriteMapper.class); }
        @Bean MinioService minio() { return mock(MinioService.class); }
        @Bean MediaCleanupService mediaCleanup() { return mock(MediaCleanupService.class); }
        @Bean ObjectMapper json() { return new ObjectMapper(); }
        @Bean LocalValidatorFactoryBean validator() { return new LocalValidatorFactoryBean(); }

        @Bean PersonalPlaylistService personal(PlaylistMapper mapper, PlaylistBindingMapper bindings,
                                               PlaylistCreateRequestMapper receipts, SongMapper songs,
                                               PlaylistAccessService access, IPlaylistService details,
                                               ObjectMapper json, Validator validator, MinioService minio,
                                               MediaCleanupService mediaCleanup) {
            return new PersonalPlaylistService(mapper, bindings, receipts, songs, access, details, json, validator, minio, mediaCleanup);
        }

        @Bean SongArtistService songArtistService(SongArtistMapper mapper, ArtistMapper artists) {
            return new SongArtistService(mapper, artists);
        }

        @Bean ArtistNameParser artistNameParser(ArtistMapper artists) { return new ArtistNameParser(artists); }

        @Bean cn.edu.seig.vibemusic.service.agent.AgentArtistResolver artistResolver(ArtistMapper artists) {
            return new cn.edu.seig.vibemusic.service.agent.AgentArtistResolver(artists);
        }

        @Bean AgentSongQueryService songQuery(AgentSongMapper mapper, SongArtistService songArtists,
                                             cn.edu.seig.vibemusic.service.agent.AgentArtistResolver artistResolver) {
            return new AgentSongQueryService(mapper, songArtists, artistResolver);
        }

        @Bean cn.edu.seig.vibemusic.service.agent.PlaylistSelectionHistoryService selectionHistory(
                AgentPendingActionMapper actions, ObjectMapper json) {
            var service = new cn.edu.seig.vibemusic.service.agent.PlaylistSelectionHistoryService(actions, json);
            // @Value 字段在纯 Java 配置里不会自动注入，这里显式给值（与 PendingActionService 同样的做法）。
            org.springframework.test.util.ReflectionTestUtils.setField(service, "lookback", 5);
            return service;
        }

        @Bean cn.edu.seig.vibemusic.service.agent.UserMusicProfileService profileService(
                UserMusicProfileMapper mapper, PlayHistoryMapper playHistories, ObjectMapper json) {
            var service = new cn.edu.seig.vibemusic.service.agent.UserMusicProfileService(mapper, playHistories, json);
            // @Value 字段在纯 Java 配置里不会自动注入，这里显式给值（与 PendingActionService 同样的做法）。
            org.springframework.test.util.ReflectionTestUtils.setField(service, "refreshTtlMinutes", 10);
            org.springframework.test.util.ReflectionTestUtils.setField(service, "topN", 5);
            return service;
        }

        @Bean AgentMemoryMapper memoryMapper(SqlSessionTemplate session) {
            return session.getMapper(AgentMemoryMapper.class);
        }

        @Bean cn.edu.seig.vibemusic.service.agent.UserMemoryPreferenceService memoryPreferenceService(
                AgentMemoryMapper memoryMapper, ArtistMapper artists,
                cn.edu.seig.vibemusic.service.agent.UserMusicProfileService profiles, ObjectMapper json) {
            var service = new cn.edu.seig.vibemusic.service.agent.UserMemoryPreferenceService(
                    memoryMapper, artists, profiles, json);
            org.springframework.test.util.ReflectionTestUtils.setField(service, "maxPerCategory", 50);
            return service;
        }

        @Bean cn.edu.seig.vibemusic.service.agent.AgentMetricsRecorder metricsRecorder(AiTaskMetricMapper mapper) {
            return new cn.edu.seig.vibemusic.service.agent.AgentMetricsRecorder(mapper);
        }

        @Bean MusicTools musicTools(AgentSongQueryService songQuery, ArtistMapper artists, PlaylistMapper playlists,
                                    cn.edu.seig.vibemusic.service.agent.PlaylistSelectionHistoryService selectionHistory,
                                    cn.edu.seig.vibemusic.service.agent.UserMemoryPreferenceService memoryPreferences) {
            return new MusicTools(songQuery, artists, playlists, selectionHistory, memoryPreferences);
        }

        @Bean PlaylistActionTools playlistActionTools(PendingActionService pendingActions) {
            return new PlaylistActionTools(pendingActions);
        }

        @Bean PendingActionService pendingActions(AgentPendingActionMapper actions, AgentSongQueryService songQuery,
                                                  PersonalPlaylistService personal, ObjectMapper json,
                                                  cn.edu.seig.vibemusic.service.agent.PlaylistSelectionHistoryService selectionHistory,
                                                  cn.edu.seig.vibemusic.service.agent.UserMemoryPreferenceService memoryPreferences) {
            PendingActionService service = new PendingActionService(actions, songQuery, personal, json, selectionHistory,
                    memoryPreferences);
            // @Value 字段在纯 Java 配置里不会自动注入，这里显式给一个较短的有效期便于测试。
            org.springframework.test.util.ReflectionTestUtils.setField(service, "ttlMinutes", 30);
            return service;
        }
    }

    protected static List<Long> ids(long... values) {
        List<Long> list = new ArrayList<>();
        for (long value : values) list.add(value);
        return list;
    }
}
