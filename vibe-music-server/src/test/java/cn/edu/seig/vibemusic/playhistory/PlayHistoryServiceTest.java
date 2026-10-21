package cn.edu.seig.vibemusic.playhistory;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.mapper.PlayHistoryMapper;
import cn.edu.seig.vibemusic.mapper.SongMapper;
import cn.edu.seig.vibemusic.model.dto.PlayHistoryQueryDTO;
import cn.edu.seig.vibemusic.model.dto.PlayHistoryRecordDTO;
import cn.edu.seig.vibemusic.model.vo.PlayHistoryVO;
import cn.edu.seig.vibemusic.result.PageResult;
import cn.edu.seig.vibemusic.service.PlayHistoryService;
import cn.edu.seig.vibemusic.service.SongArtistService;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.annotation.DbType;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 播放历史的业务规则验证：<b>真实 H2(MySQL 模式) + 真实 Mapper SQL + 真实 Service</b>。
 *
 * <p>刻意不 mock 掉 Mapper —— 要验证的正是「唯一键去重」「带 user_id 的删除真的删不到别人的行」
 * 「歌曲删除后历史级联消失」这类<b>数据库层面</b>的事实，mock 掉就什么都没验证到。
 *
 * <p>夹具：用户 1（alice）与用户 2（bob），歌曲 10/11/12（歌手 A）、13/14（歌手 B）。
 * 刻意包含「两个用户对同一首歌都有历史」的场景，用来证明隔离。
 */
class PlayHistoryServiceTest {

    private JdbcTemplate jdbc;
    private PlayHistoryService service;
    private PlayHistoryMapper mapper;
    private SongMapper songs;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:play_history_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc = new JdbcTemplate(source);
        createSchema();
        seed();

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(PlayHistoryMapper.class);
        configuration.addMapper(SongMapper.class);
        configuration.addMapper(cn.edu.seig.vibemusic.mapper.SongArtistMapper.class);
        configuration.addMapper(cn.edu.seig.vibemusic.mapper.ArtistMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        MybatisPlusInterceptor pagination = new MybatisPlusInterceptor();
        pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.H2));
        factory.setPlugins(pagination);
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        mapper = session.getMapper(PlayHistoryMapper.class);
        songs = session.getMapper(SongMapper.class);

        // SongArtistService 的装配在这里无关紧要（只影响 artists 字段），用真实 mapper 即可。
        SongArtistService songArtists = new SongArtistService(
                session.getMapper(cn.edu.seig.vibemusic.mapper.SongArtistMapper.class),
                session.getMapper(cn.edu.seig.vibemusic.mapper.ArtistMapper.class));
        service = new PlayHistoryService(mapper, songs, songArtists);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalUtil.remove();
    }

    private void createSchema() {
        jdbc.execute("CREATE TABLE tb_user(id BIGINT PRIMARY KEY, username VARCHAR(255), user_avatar VARCHAR(255))");
        jdbc.execute("CREATE TABLE tb_artist(id BIGINT PRIMARY KEY, name VARCHAR(255), gender INT, "
                + "avatar VARCHAR(255), birth DATE, area VARCHAR(64), introduction VARCHAR(1000), "
                + "status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', merged_into_artist_id BIGINT)");
        jdbc.execute("CREATE TABLE tb_album(id BIGINT PRIMARY KEY, name VARCHAR(255), artist_id BIGINT, cover_url VARCHAR(255))");
        jdbc.execute("CREATE TABLE tb_song(id BIGINT PRIMARY KEY, name VARCHAR(255), album VARCHAR(255), "
                + "album_id BIGINT, artist_id BIGINT, artist_names VARCHAR(512), style VARCHAR(64), duration VARCHAR(20), "
                + "cover_url VARCHAR(255), audio_url VARCHAR(255), release_time DATE, disc_number INT, track_number INT, "
                + "lyric CLOB, lyric_source VARCHAR(32), lyric_status INT, lyric_updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE tb_song_artist(song_id BIGINT NOT NULL, artist_id BIGINT NOT NULL, "
                + "display_order INT NOT NULL DEFAULT 1, PRIMARY KEY(song_id, artist_id))");
        // 与 sql/phase12_play_history.sql 对齐：唯一键 + 两条级联外键。
        jdbc.execute("CREATE TABLE tb_play_history(id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "user_id BIGINT NOT NULL, song_id BIGINT NOT NULL, last_played_at TIMESTAMP NOT NULL, "
                + "play_count INT NOT NULL DEFAULT 1, last_position_seconds INT, last_play_id VARCHAR(64), "
                + "UNIQUE(user_id, song_id), "
                + "FOREIGN KEY(user_id) REFERENCES tb_user(id) ON DELETE CASCADE, "
                + "FOREIGN KEY(song_id) REFERENCES tb_song(id) ON DELETE CASCADE)");
    }

    private void seed() {
        jdbc.update("INSERT INTO tb_user(id, username) VALUES (1,'alice'), (2,'bob')");
        jdbc.update("INSERT INTO tb_artist(id, name) VALUES (1,'歌手A'), (2,'歌手B')");
        jdbc.update("INSERT INTO tb_album(id, name, artist_id) VALUES (1,'专辑A',1)");
        jdbc.update("INSERT INTO tb_song(id,name,album,album_id,artist_id,artist_names,style,duration,"
                + "cover_url,audio_url) VALUES "
                + "(10,'夜色温柔','专辑A',1,1,'歌手A','流行','03:20','http://minio/10.jpg','http://minio/10.mp3'),"
                + "(11,'晚风轻语','专辑A',1,1,'歌手A','流行','04:05','http://minio/11.jpg','http://minio/11.mp3'),"
                + "(12,'星河滚烫','专辑A',1,1,'歌手A','摇滚','02:50','http://minio/12.jpg','http://minio/12.mp3'),"
                + "(13,'雨落长街',NULL,NULL,2,'歌手B','民谣','03:41','http://minio/13.jpg','http://minio/13.mp3'),"
                + "(14,'旧巷口的猫',NULL,NULL,2,'歌手B','粤语','03:12','http://minio/14.jpg','http://minio/14.mp3')");
        jdbc.update("INSERT INTO tb_song_artist(song_id, artist_id, display_order) VALUES "
                + "(10,1,1),(11,1,1),(12,1,1),(13,2,1),(14,2,1)");
    }

    // ------------------------------------------------------------------ helpers

    private static void login(Long userId) {
        ThreadLocalUtil.set(Map.of(JwtClaimsConstant.USER_ID, userId, JwtClaimsConstant.ROLE, "ROLE_USER"));
    }

    private static PlayHistoryRecordDTO play(long songId, String playId) {
        PlayHistoryRecordDTO dto = new PlayHistoryRecordDTO();
        dto.setSongId(songId);
        dto.setPlayId(playId);
        return dto;
    }

    private int rows() {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM tb_play_history", Integer.class);
        return value == null ? 0 : value;
    }

    private Integer playCount(long userId, long songId) {
        List<Integer> values = jdbc.queryForList(
                "SELECT play_count FROM tb_play_history WHERE user_id = ? AND song_id = ?",
                Integer.class, userId, songId);
        return values.isEmpty() ? null : values.get(0);
    }

    private static int status(Runnable action) {
        return ((ResponseStatusException) org.junit.jupiter.api.Assertions
                .assertThrows(ResponseStatusException.class, action::run))
                .getStatusCode().value();
    }

    // ------------------------------------------------------------------ 1. 登录用户查询自己的历史

    /**
     * 列表按「最近播放时间倒序」。
     *
     * <p><b>为什么这里要显式把时间往前推</b>：{@code DATETIME(6)} 的精度是微秒，
     * 连续三次 {@code recordPlay} 很可能落在**同一个微秒**里，此时「谁更近」在数据上
     * 就是并列的，排序只能由 tiebreaker（{@code id DESC}）决定 —— 那样断言的是
     * 插入顺序，不是「最近播放优先」。这里手动错开时间，验证的才是真正的语义。
     *
     * <p>生产环境的真实播放天然间隔几十秒到几分钟，不存在这个并列问题。
     */
    @Test
    void loggedInUserCanQueryOwnHistoryNewestFirst() {
        login(1L);
        service.recordPlay(play(10L, "p-1"));
        service.recordPlay(play(11L, "p-2"));
        // 把 song 11 的最近播放时间往前推 1 小时，模拟「先听 11、后听 10」
        jdbc.update("UPDATE tb_play_history SET last_played_at = DATEADD('HOUR', -1, last_played_at) "
                + "WHERE user_id = 1 AND song_id = 11");

        PlayHistoryQueryDTO query = new PlayHistoryQueryDTO();
        query.setPageNum(1);
        query.setPageSize(10);
        PageResult<PlayHistoryVO> page = service.page(query);

        assertThat(page.getTotal()).isEqualTo(2);
        assertThat(page.getItems()).extracting(PlayHistoryVO::getSongId)
                .containsExactly(10L, 11L);
        // 列表项必须带齐播放所需的歌曲信息，否则前端还得再查一遍
        PlayHistoryVO first = page.getItems().get(0);
        assertThat(first.getSongName()).isEqualTo("夜色温柔");
        assertThat(first.getArtistName()).isEqualTo("歌手A");
        assertThat(first.getAlbum()).isEqualTo("专辑A");
        assertThat(first.getAudioUrl()).isEqualTo("http://minio/10.mp3");
        assertThat(first.getLastPlayedAt()).isNotNull();
        assertThat(first.getPlayCount()).isEqualTo(1);
    }

    /**
     * 再次播放同一首歌会刷新它的最近播放时间，从而排到最前。
     *
     * <p>用「把另一首的时间推早」来制造确定的先后关系，理由同上。
     */
    @Test
    void replayingASongMovesItBackToTheTop() {
        login(1L);
        service.recordPlay(play(10L, "p-1"));
        jdbc.update("UPDATE tb_play_history SET last_played_at = DATEADD('HOUR', -2, last_played_at) "
                + "WHERE user_id = 1 AND song_id = 10");
        service.recordPlay(play(11L, "p-2"));
        // 关键：把 11 的时间推早 1 小时，而不是只把 10 推早。
        // 两次 recordPlay 可能落在同一毫秒（Windows 上 LocalDateTime.now 的实际粒度就是毫秒），
        // 时间相同就只能靠 id DESC 决胜负，11 反而排前面 —— 测试会随机器快慢时红时绿。
        // 推早 11 之后：若 10 的 last_played_at 真的被刷新，它必然排在 11 之前；
        // 若没刷新（= 回放没更新时间的 bug），10 仍是 2 小时前，断言立刻失败。
        jdbc.update("UPDATE tb_play_history SET last_played_at = DATEADD('HOUR', -1, last_played_at) "
                + "WHERE user_id = 1 AND song_id = 11");
        service.recordPlay(play(10L, "p-3")); // 再听一次 10，它应当回到最前

        PlayHistoryQueryDTO query = new PlayHistoryQueryDTO();
        query.setPageNum(1);
        query.setPageSize(10);
        PageResult<PlayHistoryVO> page = service.page(query);

        assertThat(page.getItems()).extracting(PlayHistoryVO::getSongId)
                .containsExactly(10L, 11L);
        assertThat(page.getItems().get(0).getPlayCount()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 2. 用户之间数据隔离

    @Test
    void differentUsersNeverSeeEachOthersHistory() {
        login(1L);
        service.recordPlay(play(10L, "a-1"));
        service.recordPlay(play(13L, "a-2"));

        login(2L);
        service.recordPlay(play(10L, "b-1")); // 同一首歌，不同用户：必须是两行

        PlayHistoryQueryDTO query = new PlayHistoryQueryDTO();
        query.setPageNum(1);
        query.setPageSize(10);

        PageResult<PlayHistoryVO> bob = service.page(query);
        assertThat(bob.getTotal()).isEqualTo(1);
        assertThat(bob.getItems()).extracting(PlayHistoryVO::getSongId).containsExactly(10L);

        login(1L);
        PageResult<PlayHistoryVO> alice = service.page(query);
        assertThat(alice.getTotal()).isEqualTo(2);
        assertThat(alice.getItems()).extracting(PlayHistoryVO::getSongId)
                .containsExactlyInAnyOrder(10L, 13L);
        assertThat(rows()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ 3. 未登录不能访问

    @Test
    void guestCannotReadOrWriteHistory() {
        ThreadLocalUtil.remove();
        PlayHistoryQueryDTO query = new PlayHistoryQueryDTO();
        query.setPageNum(1);
        query.setPageSize(10);

        assertThat(status(() -> service.recordPlay(play(10L, "g-1")))).isEqualTo(401);
        assertThat(status(() -> service.page(query))).isEqualTo(401);
        assertThat(status(() -> service.recent(10))).isEqualTo(401);
        assertThat(status(() -> service.delete(1L))).isEqualTo(401);
        assertThat(status(service::clear)).isEqualTo(401);
        assertThat(rows()).isZero();
    }

    /**
     * 管理员身份同样拿不到用户历史：ROLE 不是 ROLE_USER 时
     * {@code PlaylistAccessService.requireUserId()} 会拒绝。
     */
    @Test
    void nonUserRoleIsRejectedEvenWithAUserIdClaim() {
        ThreadLocalUtil.set(Map.of(JwtClaimsConstant.USER_ID, 1L, JwtClaimsConstant.ROLE, "ROLE_ADMIN"));

        assertThat(status(() -> service.recordPlay(play(10L, "x-1")))).isEqualTo(401);
        assertThat(rows()).isZero();
    }

    // ------------------------------------------------------------------ 4. 重复播放不产生重复记录

    @Test
    void replayingSameSongUpdatesOneRowInsteadOfAppending() {
        login(1L);
        service.recordPlay(play(10L, "p-1"));
        service.recordPlay(play(10L, "p-2"));
        service.recordPlay(play(10L, "p-3"));

        assertThat(rows()).isEqualTo(1);
        assertThat(playCount(1L, 10L)).isEqualTo(3);
    }

    /**
     * 同一个 playId 重复上报（网络重试 / 事件重复触发）只计一次。
     * 换一个 playId 才是新的一次播放。
     */
    @Test
    void identicalPlayIdIsCountedOnce() {
        login(1L);
        assertThat(service.recordPlay(play(10L, "same-id"))).isTrue();
        assertThat(service.recordPlay(play(10L, "same-id"))).isFalse();
        assertThat(service.recordPlay(play(10L, "same-id"))).isFalse();

        assertThat(playCount(1L, 10L)).isEqualTo(1);
        // 但最近播放时间仍应被刷新（用户确实又播了一次，只是不重复计数）
        service.recordPlay(play(10L, "another-id"));
        assertThat(playCount(1L, 10L)).isEqualTo(2);
    }

    /** 不传 playId 的老客户端：不做幂等，每次上报都算一次播放（不能因此丢历史）。 */
    @Test
    void missingPlayIdStillRecordsButWithoutDeduplication() {
        login(1L);
        assertThat(service.recordPlay(play(10L, null))).isTrue();
        assertThat(service.recordPlay(play(10L, null))).isTrue();

        assertThat(rows()).isEqualTo(1);
        assertThat(playCount(1L, 10L)).isEqualTo(2);
    }

    /**
     * 回归：真实 MySQL 上「重复播放不累加」的 bug。
     *
     * <p><b>背景</b>：原实现用 {@code INSERT ... ON DUPLICATE KEY UPDATE id = id}
     * 的返回值判断「是不是首次插入」。MySQL Connector/J 默认是 found-rows 语义
     * （{@code useAffectedRows=false}），命中但未改动也返回 1，于是永远走不到累加分支 ——
     * 播放次数恒为 1。而 H2 返回 0，所以当时的测试全绿，生产却坏了。
     *
     * <p>这个用例直接断言「计数必须真的涨」，且<b>不依赖 affected-rows 语义</b>：
     * 无论驱动怎么返回，第二次播放后 {@code play_count} 都必须是 2。
     * 换任何数据库跑它都应该通过。
     */
    @Test
    void replayingTheSameSongAlwaysIncrementsTheCount() {
        login(1L);
        service.recordPlay(play(10L, "regress-1"));
        assertThat(playCount(1L, 10L)).isEqualTo(1);

        service.recordPlay(play(10L, "regress-2"));
        assertThat(playCount(1L, 10L))
                .as("第二次播放必须把 play_count 加到 2（affected-rows 语义不能影响计数）")
                .isEqualTo(2);

        service.recordPlay(play(10L, "regress-3"));
        assertThat(playCount(1L, 10L)).isEqualTo(3);
        assertThat(rows()).isEqualTo(1);
    }

    /**
     * 直接验证「冲突走累加」这条路径本身：先手工插一行，
     * 再让 Service 记录同一首歌 —— 它必然走 DuplicateKeyException 分支。
     */
    @Test
    void existingRowIsAccumulatedNotReinserted() {
        login(1L);
        jdbc.update("INSERT INTO tb_play_history(user_id, song_id, last_played_at, play_count, last_play_id) "
                + "VALUES (1, 10, CURRENT_TIMESTAMP, 5, 'old-play')");

        assertThat(service.recordPlay(play(10L, "new-play"))).isTrue();

        assertThat(rows()).isEqualTo(1);
        assertThat(playCount(1L, 10L)).isEqualTo(6);
    }

    /**
     * 并发上报同一首歌：唯一键保证只留一行，{@code play_count + 1} 由数据库原子累加，
     * 不丢计数也不抛唯一键冲突。
     */
    @Test
    void concurrentPlaysOfTheSameSongKeepOneRowAndDoNotLoseCounts() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> jobs = new java.util.ArrayList<>();
            for (int i = 0; i < 16; i++) {
                String playId = "concurrent-" + i;
                jobs.add(pool.submit(() -> {
                    try {
                        start.await();
                        login(1L);
                        service.recordPlay(play(10L, playId));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        ThreadLocalUtil.remove();
                    }
                }));
            }
            start.countDown();
            for (Future<?> job : jobs) {
                job.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(rows()).isEqualTo(1);
        assertThat(playCount(1L, 10L)).isEqualTo(16);
    }

    // ------------------------------------------------------------------ 5. 有效播放与无效播放

    /**
     * 「有效」由客户端判定：只有达到阈值的播放才会调用这个接口。
     * 服务端能验证的是「不被调用的东西不会留下记录」—— 页面加载/误点击/初始化
     * 根本不会发请求，所以这里断言的是「只调一次就只有一条」。
     */
    @Test
    void onlyExplicitReportsCreateHistory() {
        login(1L);
        assertThat(rows()).isZero();

        service.recordPlay(play(10L, "effective-1"));
        assertThat(rows()).isEqualTo(1);
        assertThat(playCount(1L, 10L)).isEqualTo(1);
    }

    /** 非法输入一律拒绝，且不在库里留痕。 */
    @Test
    void invalidPayloadsAreRejectedWithoutWriting() {
        login(1L);

        PlayHistoryRecordDTO nullSong = new PlayHistoryRecordDTO();
        assertThat(status(() -> service.recordPlay(nullSong))).isEqualTo(400);
        assertThat(status(() -> service.recordPlay(null))).isEqualTo(400);

        PlayHistoryRecordDTO negative = new PlayHistoryRecordDTO();
        negative.setSongId(-5L);
        assertThat(status(() -> service.recordPlay(negative))).isEqualTo(400);

        assertThat(status(() -> service.recordPlay(play(999L, "ghost")))).isEqualTo(404);

        assertThat(rows()).isZero();
    }

    /** 播放进度只用于留痕，不参与计数；负数在 DTO 校验层就被拦住（见 @Min）。 */
    @Test
    void positionIsStoredButDoesNotAffectCounting() {
        login(1L);
        PlayHistoryRecordDTO dto = play(10L, "pos-1");
        dto.setPositionSeconds(42);
        service.recordPlay(dto);

        Integer stored = jdbc.queryForObject(
                "SELECT last_position_seconds FROM tb_play_history WHERE user_id = 1 AND song_id = 10",
                Integer.class);
        assertThat(stored).isEqualTo(42);
        assertThat(playCount(1L, 10L)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 6. 删除单条只能删自己的

    @Test
    void deletingOneRecordOnlyRemovesTheCallersOwnRow() {
        login(1L);
        service.recordPlay(play(10L, "a-1"));
        long aliceHistoryId = jdbc.queryForObject(
                "SELECT id FROM tb_play_history WHERE user_id = 1 AND song_id = 10", Long.class);

        login(2L);
        service.recordPlay(play(10L, "b-1"));

        // bob 拿 alice 的 historyId 来删：必须 404，且 alice 的行还在
        assertThat(status(() -> service.delete(aliceHistoryId))).isEqualTo(404);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_play_history WHERE id = ?", Integer.class, aliceHistoryId))
                .isEqualTo(1);

        // bob 删自己的那条才成功
        long bobHistoryId = jdbc.queryForObject(
                "SELECT id FROM tb_play_history WHERE user_id = 2 AND song_id = 10", Long.class);
        service.delete(bobHistoryId);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_play_history WHERE id = ?", Integer.class, bobHistoryId))
                .isZero();
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void deletingNonExistentOrInvalidRecordIsRejected() {
        login(1L);
        assertThat(status(() -> service.delete(123456L))).isEqualTo(404);
        assertThat(status(() -> service.delete(0L))).isEqualTo(400);
        assertThat(status(() -> service.delete(-1L))).isEqualTo(400);
        assertThat(status(() -> service.delete(null))).isEqualTo(400);
    }

    // ------------------------------------------------------------------ 7. 清空不影响其他用户

    @Test
    void clearingHistoryNeverTouchesOtherUsers() {
        login(1L);
        service.recordPlay(play(10L, "a-1"));
        service.recordPlay(play(11L, "a-2"));

        login(2L);
        service.recordPlay(play(10L, "b-1"));
        service.recordPlay(play(13L, "b-2"));
        service.recordPlay(play(14L, "b-3"));

        login(1L);
        long cleared = service.clear();

        assertThat(cleared).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_play_history WHERE user_id = 1", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_play_history WHERE user_id = 2", Integer.class)).isEqualTo(3);
    }

    // ------------------------------------------------------------------ 8. 分页 / 空结果 / 已删除歌曲

    @Test
    void emptyHistoryReturnsEmptyPageNotError() {
        login(1L);
        PlayHistoryQueryDTO query = new PlayHistoryQueryDTO();
        query.setPageNum(1);
        query.setPageSize(10);

        PageResult<PlayHistoryVO> page = service.page(query);

        assertThat(page.getTotal()).isZero();
        assertThat(page.getItems()).isEmpty();
        assertThat(service.recent(10)).isEmpty();
    }

    /**
     * 分页不重叠、总数一致。
     *
     * <p>排序依据是 {@code last_played_at DESC, id DESC}：连续写入的时间可能落在同一微秒，
     * 此时 {@code id DESC} 是 tiebreaker —— 排序仍然确定（不会两页返回同一条），
     * 这正是本用例要验证的「分页稳定」，而不是「插入顺序」。
     */
    @Test
    void paginationSplitsHistoryAndKeepsStableOrder() {
        login(1L);
        for (long songId = 10; songId <= 14; songId++) {
            service.recordPlay(play(songId, "p-" + songId));
        }

        PlayHistoryQueryDTO first = new PlayHistoryQueryDTO();
        first.setPageNum(1);
        first.setPageSize(2);
        PageResult<PlayHistoryVO> page1 = service.page(first);

        PlayHistoryQueryDTO second = new PlayHistoryQueryDTO();
        second.setPageNum(2);
        second.setPageSize(2);
        PageResult<PlayHistoryVO> page2 = service.page(second);

        assertThat(page1.getTotal()).isEqualTo(5);
        assertThat(page2.getTotal()).isEqualTo(5);
        assertThat(page1.getItems()).hasSize(2);
        assertThat(page2.getItems()).hasSize(2);
        // 两页之间不能有重叠
        assertThat(page1.getItems()).extracting(PlayHistoryVO::getSongId)
                .doesNotContainAnyElementsOf(
                        page2.getItems().stream().map(PlayHistoryVO::getSongId).toList());
        // 重复查询同一页必须返回同样的结果（排序是确定的，不是数据库的随机顺序）
        assertThat(service.page(first).getItems()).extracting(PlayHistoryVO::getSongId)
                .containsExactlyElementsOf(
                        page1.getItems().stream().map(PlayHistoryVO::getSongId).toList());
    }

    @Test
    void invalidPaginationIsRejected() {
        login(1L);
        PlayHistoryQueryDTO zeroPage = new PlayHistoryQueryDTO();
        zeroPage.setPageNum(0);
        zeroPage.setPageSize(10);
        assertThat(status(() -> service.page(zeroPage))).isEqualTo(400);

        PlayHistoryQueryDTO hugePageSize = new PlayHistoryQueryDTO();
        hugePageSize.setPageNum(1);
        hugePageSize.setPageSize(101);
        assertThat(status(() -> service.page(hugePageSize))).isEqualTo(400);

        assertThat(status(() -> service.page(null))).isEqualTo(400);
    }

    @Test
    void songNameFilterOnlyMatchesWithinOwnHistory() {
        login(1L);
        service.recordPlay(play(10L, "p-1"));  // 夜色温柔
        service.recordPlay(play(13L, "p-2"));  // 雨落长街

        PlayHistoryQueryDTO query = new PlayHistoryQueryDTO();
        query.setPageNum(1);
        query.setPageSize(10);
        query.setSongName("夜色");
        PageResult<PlayHistoryVO> page = service.page(query);

        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getItems().get(0).getSongId()).isEqualTo(10L);
    }

    /**
     * 歌曲被删除后，历史行必须跟着消失（ON DELETE CASCADE），
     * 并且读接口绝不能返回一条「指向不存在歌曲」的记录。
     */
    @Test
    void deletingSongRemovesItsHistoryRows() {
        login(1L);
        service.recordPlay(play(10L, "p-1"));
        service.recordPlay(play(11L, "p-2"));
        assertThat(rows()).isEqualTo(2);

        jdbc.update("DELETE FROM tb_song WHERE id = 10");

        assertThat(rows()).isEqualTo(1);
        PlayHistoryQueryDTO query = new PlayHistoryQueryDTO();
        query.setPageNum(1);
        query.setPageSize(10);
        PageResult<PlayHistoryVO> page = service.page(query);
        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getItems()).extracting(PlayHistoryVO::getSongId).containsExactly(11L);
    }

    /**
     * 即使外键被绕过（例如从旧库恢复了一份不完整的 dump），残留行也不能被返回：
     * 读查询是 INNER JOIN tb_song，没有对应歌曲的行天然不会出现。
     *
     * <p>为了造出这种「本不该存在」的行，临时把外键去掉 —— 否则数据库会直接拒绝插入。
     * 这正是本用例要覆盖的场景：约束失效时，读路径仍必须自己兜住。
     */
    @Test
    void orphanHistoryRowIsNeverReturned() {
        login(1L);
        service.recordPlay(play(10L, "p-1"));
        jdbc.execute("ALTER TABLE tb_play_history DROP CONSTRAINT IF EXISTS CONSTRAINT_56406");
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            jdbc.update("DELETE FROM tb_play_history");
            jdbc.update("INSERT INTO tb_play_history(user_id, song_id, last_played_at, play_count) "
                    + "VALUES (1, 999, CURRENT_TIMESTAMP, 3)");
        } finally {
            jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }

        PlayHistoryQueryDTO query = new PlayHistoryQueryDTO();
        query.setPageNum(1);
        query.setPageSize(10);
        PageResult<PlayHistoryVO> page = service.page(query);

        assertThat(page.getTotal()).isZero();
        assertThat(page.getItems()).isEmpty();
        assertThat(service.recent(10)).isEmpty();
    }

    @Test
    void recentRespectsLimitAndClampsOutOfRangeValues() {
        login(1L);
        for (long songId = 10; songId <= 14; songId++) {
            service.recordPlay(play(songId, "p-" + songId));
        }

        assertThat(service.recent(2)).hasSize(2);
        assertThat(service.recent(0)).hasSize(5);   // 非正数 -> 默认 20，实际只有 5 条
        assertThat(service.recent(null)).hasSize(5);
        assertThat(service.recent(9999)).hasSize(5); // 超过上限夹到 50，实际只有 5 条
    }

    /** 用户被删除时历史一并消失（FK ON DELETE CASCADE）。 */
    @Test
    void deletingUserRemovesTheirHistory() {
        login(1L);
        service.recordPlay(play(10L, "p-1"));
        login(2L);
        service.recordPlay(play(11L, "p-2"));
        assertThat(rows()).isEqualTo(2);

        jdbc.update("DELETE FROM tb_user WHERE id = 1");

        assertThat(rows()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT user_id FROM tb_play_history", Long.class)).isEqualTo(2L);
    }
}
