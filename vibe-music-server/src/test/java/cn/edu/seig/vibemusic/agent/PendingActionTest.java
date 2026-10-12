package cn.edu.seig.vibemusic.agent;

import cn.edu.seig.vibemusic.model.agent.PlaylistDraft;
import cn.edu.seig.vibemusic.model.agent.SongRef;
import cn.edu.seig.vibemusic.model.dto.AgentPlaylistDraftDTO;
import cn.edu.seig.vibemusic.model.vo.PendingActionVO;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 第 10 项验收：确认前不写入、确认时重新鉴权、重复/并发确认幂等、取消与过期不可执行、刷新后可查询。
 *
 * <p>所有断言都落在数据库事实上（歌单行数、绑定行数、任务状态），而不是只看返回值。
 */
class PendingActionTest extends AbstractAgentTest {

    private PendingActionVO prepare(String title, List<Long> songIds) {
        return pendingActions.prepare(1L, "user_chat_id_1",
                new PlaylistDraft(title, "简介", "流行", false, songIds));
    }

    private static void assertStatus(int expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(ResponseStatusException.class, action).getStatusCode().value());
    }

    @Test
    @DisplayName("准备阶段不写任何歌单数据；确认后恰好创建一个歌单并正确关联歌曲")
    void prepareWritesNothingAndConfirmCreatesExactlyOnce() {
        login(1L);
        PendingActionVO preview = prepare("夜间驾驶", ids(10, 11, 12));

        assertEquals("PENDING", preview.getStatus());
        assertEquals(3, preview.getSongCount());
        assertEquals(0, count("tb_playlist"), "确认之前不得创建歌单");
        assertEquals(0, count("tb_playlist_binding"));
        assertEquals(1, count("tb_agent_pending_action"));

        PendingActionVO done = pendingActions.confirm(preview.getTaskId(), preview.getVersion());
        assertEquals("SUCCEEDED", done.getStatus());
        assertNotNull(done.getPlaylistId());
        assertEquals(1, count("tb_playlist"));
        assertEquals(3, count("tb_playlist_binding"));
        assertEquals(1L, jdbc.queryForObject("SELECT owner_user_id FROM tb_playlist WHERE id = ?",
                Long.class, done.getPlaylistId()));
        assertEquals("夜间驾驶", jdbc.queryForObject("SELECT title FROM tb_playlist WHERE id = ?",
                String.class, done.getPlaylistId()));
    }

    @Test
    @DisplayName("预览内容与最终写入内容一致：预览里的歌曲 ID 与数据库绑定完全吻合")
    void previewMatchesExecutedContent() {
        login(1L);
        PendingActionVO preview = prepare("一致性检查", ids(10, 13));
        PendingActionVO done = pendingActions.confirm(preview.getTaskId(), null);

        List<Long> previewIds = preview.getSongs().stream().map(SongRef::songId).sorted().toList();
        List<Long> boundIds = jdbc.queryForList(
                "SELECT song_id FROM tb_playlist_binding WHERE playlist_id = ? ORDER BY song_id",
                Long.class, done.getPlaylistId());
        assertEquals(previewIds, boundIds);
        assertEquals(preview.getTitle(), jdbc.queryForObject("SELECT title FROM tb_playlist WHERE id = ?",
                String.class, done.getPlaylistId()));
    }

    @Test
    @DisplayName("重复确认返回同一个歌单，不会重复创建")
    void duplicateConfirmIsIdempotent() {
        login(1L);
        PendingActionVO preview = prepare("重复确认", ids(10));

        PendingActionVO first = pendingActions.confirm(preview.getTaskId(), null);
        PendingActionVO second = pendingActions.confirm(preview.getTaskId(), null);
        PendingActionVO third = pendingActions.confirm(preview.getTaskId(), null);

        assertEquals(first.getPlaylistId(), second.getPlaylistId());
        assertEquals(first.getPlaylistId(), third.getPlaylistId());
        assertEquals("SUCCEEDED", third.getStatus());
        assertEquals(1, count("tb_playlist"));
        assertEquals(1, count("tb_playlist_binding"));
    }

    @Test
    @DisplayName("并发确认只创建一个歌单，其余请求复用已落库的结果")
    void concurrentConfirmCreatesExactlyOnePlaylist() throws Exception {
        login(1L);
        PendingActionVO preview = prepare("并发确认", ids(10, 11));

        int workers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    login(1L);
                    start.await();
                    try {
                        return pendingActions.confirm(preview.getTaskId(), null).getPlaylistId();
                    } catch (ResponseStatusException e) {
                        // 竞争失败的一方允许收到 409；关键是不能多建歌单。
                        return null;
                    } finally {
                        ThreadLocalUtil.remove();
                    }
                }));
            }
            start.countDown();
            List<Long> created = new ArrayList<>();
            for (Future<Long> future : futures) {
                Long value = future.get(20, TimeUnit.SECONDS);
                if (value != null) created.add(value);
            }
            assertFalse(created.isEmpty(), "至少应有一个请求成功");
            assertEquals(1, created.stream().distinct().count(), "所有成功请求必须指向同一个歌单");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, count("tb_playlist"));
        assertEquals(2, count("tb_playlist_binding"));
    }

    @Test
    @DisplayName("取消后不可再确认，也不会创建歌单")
    void cancelledTaskCannotBeConfirmed() {
        login(1L);
        PendingActionVO preview = prepare("取消测试", ids(10));
        PendingActionVO cancelled = pendingActions.cancel(preview.getTaskId());
        assertEquals("CANCELLED", cancelled.getStatus());

        assertStatus(409, () -> pendingActions.confirm(preview.getTaskId(), null));
        assertEquals(0, count("tb_playlist"));
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM tb_agent_pending_action WHERE task_id = ?",
                String.class, preview.getTaskId()));
    }

    @Test
    @DisplayName("过期任务不可确认，并落成 EXPIRED 终态")
    void expiredTaskCannotBeConfirmed() {
        login(1L);
        PendingActionVO preview = prepare("过期测试", ids(10));
        // 把有效期改到过去，模拟任务过期。
        jdbc.update("UPDATE tb_agent_pending_action SET expires_at = DATEADD('MINUTE', -5, CURRENT_TIMESTAMP) "
                + "WHERE task_id = ?", preview.getTaskId());

        assertStatus(410, () -> pendingActions.confirm(preview.getTaskId(), null));
        assertEquals(0, count("tb_playlist"));
        assertEquals("EXPIRED", jdbc.queryForObject("SELECT status FROM tb_agent_pending_action WHERE task_id = ?",
                String.class, preview.getTaskId()));
        assertEquals("EXPIRED", pendingActions.get(preview.getTaskId()).getStatus());
    }

    @Test
    @DisplayName("其他用户既不能查询也不能确认、取消别人的任务（一律 404，不泄漏存在性）")
    void otherUserCannotTouchTheTask() {
        login(1L);
        PendingActionVO preview = prepare("越权测试", ids(10));

        login(2L);
        assertStatus(404, () -> pendingActions.get(preview.getTaskId()));
        assertStatus(404, () -> pendingActions.confirm(preview.getTaskId(), null));
        assertStatus(404, () -> pendingActions.cancel(preview.getTaskId()));
        assertEquals(0, count("tb_playlist"));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM tb_agent_pending_action WHERE task_id = ?",
                String.class, preview.getTaskId()));
    }

    @Test
    @DisplayName("未登录（游客）不能确认任务")
    void guestCannotConfirm() {
        login(1L);
        PendingActionVO preview = prepare("游客确认", ids(10));
        logout();
        assertStatus(401, () -> pendingActions.confirm(preview.getTaskId(), null));
        assertEquals(0, count("tb_playlist"));
    }

    @Test
    @DisplayName("修改预览会提升版本号；用旧版本号确认会被拒绝，用新版本号确认写入新内容")
    void updateBumpsVersionAndStaleVersionIsRejected() {
        login(1L);
        PendingActionVO preview = prepare("初版名称", ids(10));

        AgentPlaylistDraftDTO edit = new AgentPlaylistDraftDTO();
        edit.setTitle("修改后的名称");
        edit.setStyle("摇滚");
        edit.setSongIds(ids(10, 12));
        edit.setVersion(preview.getVersion());
        PendingActionVO updated = pendingActions.update(preview.getTaskId(), edit);

        assertEquals(2, updated.getVersion());
        assertEquals("修改后的名称", updated.getTitle());
        assertEquals(2, updated.getSongCount());
        assertEquals(0, count("tb_playlist"), "修改预览同样不得写入歌单");

        assertStatus(409, () -> pendingActions.confirm(preview.getTaskId(), preview.getVersion()));
        PendingActionVO done = pendingActions.confirm(preview.getTaskId(), updated.getVersion());
        assertEquals("修改后的名称", jdbc.queryForObject("SELECT title FROM tb_playlist WHERE id = ?",
                String.class, done.getPlaylistId()));
        assertEquals(2, count("tb_playlist_binding"));
        assertEquals(ids(10, 12), jdbc.queryForList(
                "SELECT song_id FROM tb_playlist_binding WHERE playlist_id = ? ORDER BY song_id",
                Long.class, done.getPlaylistId()));
    }

    @Test
    @DisplayName("用过期版本号修改会被拒绝，避免两个标签页互相覆盖")
    void staleVersionUpdateIsRejected() {
        login(1L);
        PendingActionVO preview = prepare("并发编辑", ids(10));
        AgentPlaylistDraftDTO first = new AgentPlaylistDraftDTO();
        first.setTitle("第一版");
        first.setSongIds(ids(10));
        first.setVersion(preview.getVersion());
        pendingActions.update(preview.getTaskId(), first);

        AgentPlaylistDraftDTO stale = new AgentPlaylistDraftDTO();
        stale.setTitle("过期版本");
        stale.setSongIds(ids(10));
        stale.setVersion(preview.getVersion());
        assertStatus(409, () -> pendingActions.update(preview.getTaskId(), stale));
        assertEquals("第一版", pendingActions.get(preview.getTaskId()).getTitle());
    }

    @Test
    @DisplayName("刷新页面后可查询：待确认列表与单个任务状态都返回真实数据")
    void refreshCanQueryRealState() {
        login(1L);
        PendingActionVO preview = prepare("刷新查询", ids(10, 11));
        PendingActionVO another = prepare("第二个待确认", ids(12));

        List<PendingActionVO> pending = pendingActions.pending("user_chat_id_1");
        assertEquals(2, pending.size());
        assertTrue(pending.stream().allMatch(vo -> "PENDING".equals(vo.getStatus())));
        assertTrue(pending.stream().anyMatch(vo -> preview.getTaskId().equals(vo.getTaskId())));

        PendingActionVO queried = pendingActions.get(preview.getTaskId());
        assertEquals(2, queried.getSongCount());
        assertEquals(List.of(10L, 11L), queried.getSongs().stream().map(SongRef::songId).toList());

        pendingActions.confirm(preview.getTaskId(), null);
        PendingActionVO afterConfirm = pendingActions.get(preview.getTaskId());
        assertEquals("SUCCEEDED", afterConfirm.getStatus());
        assertNotNull(afterConfirm.getPlaylistId());
        // 已成功的任务不再出现在待确认列表里。
        assertEquals(1, pendingActions.pending("user_chat_id_1").size());
        assertNotNull(another.getTaskId());
    }

    @Test
    @DisplayName("确认时歌曲已失效 → 失败被如实记录，不谎报成功")
    void confirmFailsWhenSongDisappeared() {
        login(1L);
        PendingActionVO preview = prepare("歌曲失效", ids(10, 11));
        // 预览生成后歌曲被删除（模拟业务数据变化）。
        jdbc.update("DELETE FROM tb_playlist_binding WHERE song_id = 11");
        jdbc.update("DELETE FROM tb_song WHERE id = 11");

        assertStatus(404, () -> pendingActions.confirm(preview.getTaskId(), null));
        assertEquals(0, count("tb_playlist"), "失败时必须整体回滚");
        assertEquals(0, count("tb_playlist_binding"));
        String status = jdbc.queryForObject("SELECT status FROM tb_agent_pending_action WHERE task_id = ?",
                String.class, preview.getTaskId());
        assertEquals("FAILED", status);
        PendingActionVO failed = pendingActions.get(preview.getTaskId());
        assertEquals("FAILED", failed.getStatus());
        assertNotNull(failed.getErrorMessage());
        assertNull(failed.getPlaylistId());
    }

    @Test
    @DisplayName("歌曲数量为 0 或超出上限的草稿在准备阶段就被拒绝")
    void invalidDraftsAreRejectedBeforePersisting() {
        login(1L);
        assertStatus(400, () -> prepare("空歌单", List.of()));
        assertStatus(400, () -> prepare("   ", ids(10)));
        List<Long> tooMany = new ArrayList<>();
        for (long i = 0; i < 101; i++) tooMany.add(10L + i);
        assertStatus(400, () -> prepare("超长歌单", tooMany));
        assertEquals(0, count("tb_agent_pending_action"));
        assertEquals(0, count("tb_playlist"));
    }

    @Test
    @DisplayName("待确认任务持久化在数据库而不是内存：换一个服务实例也能查到")
    void taskIsPersistedInDatabase() {
        login(1L);
        PendingActionVO preview = prepare("持久化验证", ids(10));
        String draftJson = jdbc.queryForObject("SELECT draft_json FROM tb_agent_pending_action WHERE task_id = ?",
                String.class, preview.getTaskId());
        String previewJson = jdbc.queryForObject("SELECT preview_json FROM tb_agent_pending_action WHERE task_id = ?",
                String.class, preview.getTaskId());
        assertTrue(draftJson.contains("持久化验证"));
        assertTrue(previewJson.contains("http://minio/10.jpg"), "预览快照应包含歌曲封面等展示数据");
        assertEquals(1, (int) jdbc.queryForObject("SELECT version FROM tb_agent_pending_action WHERE task_id = ?",
                Integer.class, preview.getTaskId()));
    }
}
