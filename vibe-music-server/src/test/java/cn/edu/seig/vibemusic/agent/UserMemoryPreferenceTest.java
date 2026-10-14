package cn.edu.seig.vibemusic.agent;

import cn.edu.seig.vibemusic.model.dto.MemoryPreferenceRequest;
import cn.edu.seig.vibemusic.service.agent.UserMemoryPreferenceService;
import cn.edu.seig.vibemusic.service.agent.UserMusicProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 手动偏好与 AI 记忆的数据库级测试。
 *
 * <p>覆盖：CRUD 与归属隔离、校验、排除优先、画像刷新不覆盖手动偏好、
 * 个性化开关、清除自动记忆的数据范围、自动画像的「只补充」语义。
 * 模型无关 —— 合并是纯数据逻辑，这正是它可以被数据库级验证的原因。
 */
class UserMemoryPreferenceTest extends AbstractAgentTest {

    @Autowired UserMemoryPreferenceService memory;
    @Autowired UserMusicProfileService profileService;

    private static MemoryPreferenceRequest request(String category, Long targetId, String targetValue) {
        MemoryPreferenceRequest request = new MemoryPreferenceRequest();
        request.setCategory(category);
        request.setTargetId(targetId);
        request.setTargetValue(targetValue);
        return request;
    }

    private void create(Long userId, String category, Long targetId, String targetValue) {
        memory.create(userId, request(category, targetId, targetValue));
    }

    private void assertCreateRejected(Long userId, String category, Long targetId, String targetValue,
                                      HttpStatus status) {
        assertThatThrownBy(() -> memory.create(userId, request(category, targetId, targetValue)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }

    // ------------------------------------------------------------ CRUD 与归属

    @Test
    void createResolvesCanonicalArtistNameAndReadsBack() {
        create(1L, "FAVORITE_ARTIST", 1L, "随便输的名字也会被忽略");

        var overview = memory.overview(1L);
        assertThat(overview.preferences()).hasSize(1);
        assertThat(overview.preferences().get(0).getTargetId()).isEqualTo(1L);
        // 歌手名由后端按 id 解析，客户端传入的 value 不被信任
        assertThat(overview.preferences().get(0).getTargetValue()).isEqualTo("测试歌手A");
    }

    @Test
    void userACannotSeeOrModifyUserBPreferences() {
        create(1L, "FAVORITE_ARTIST", 1L, null);
        create(2L, "PREFERENCE_NOTE", null, "只听粤语歌");

        // 列表互不可见
        assertThat(memory.overview(1L).preferences()).hasSize(1);
        assertThat(memory.overview(2L).preferences()).hasSize(1);
        assertThat(memory.overview(1L).preferences().get(0).getCategory()).isEqualTo("FAVORITE_ARTIST");

        Long user1PrefId = memory.overview(1L).preferences().get(0).getId();

        // 用户 2 修改 / 删除用户 1 的偏好 → 404（不区分不存在与不属于你）
        assertThatThrownBy(() -> memory.update(2L, user1PrefId, request("PREFERENCE_NOTE", null, "劫持")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> memory.delete(2L, user1PrefId))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        // 数据原封不动
        assertThat(memory.overview(1L).preferences()).hasSize(1);
    }

    // ------------------------------------------------------------ 校验

    @Test
    void validationRejectsInvalidInput() {
        assertCreateRejected(1L, "NOT_A_CATEGORY", null, "x", HttpStatus.BAD_REQUEST);
        // 歌手类：缺 targetId / 歌手不存在
        assertCreateRejected(1L, "FAVORITE_ARTIST", null, null, HttpStatus.BAD_REQUEST);
        assertCreateRejected(1L, "EXCLUDE_ARTIST", 999L, null, HttpStatus.BAD_REQUEST);
        // 风格类：空值 / 不在派生风格集合
        assertCreateRejected(1L, "FAVORITE_STYLE", null, "  ", HttpStatus.BAD_REQUEST);
        assertCreateRejected(1L, "EXCLUDE_STYLE", null, "不存在的风格", HttpStatus.BAD_REQUEST);
        // 文本类：空 / 超长
        assertCreateRejected(1L, "PREFERENCE_NOTE", null, "   ", HttpStatus.BAD_REQUEST);
        assertCreateRejected(1L, "EXCLUDE_NOTE", null, "长".repeat(201), HttpStatus.BAD_REQUEST);
    }

    @Test
    void duplicatePreferenceIsRejected() {
        create(1L, "FAVORITE_STYLE", null, "流行");
        assertCreateRejected(1L, "FAVORITE_STYLE", null, " 流行 ", HttpStatus.CONFLICT);
        // 更新成另一条已存在的值同样被拒
        create(1L, "FAVORITE_STYLE", null, "摇滚");
        Long secondId = memory.overview(1L).preferences().get(1).getId();
        assertThatThrownBy(() -> memory.update(1L, secondId, request("FAVORITE_STYLE", null, "流行")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    // ------------------------------------------------------------ 合并规则

    @Test
    void exclusionWinsOverLikeAndReportsConflict() {
        create(1L, "FAVORITE_ARTIST", 1L, null);
        create(1L, "EXCLUDE_ARTIST", 1L, null);
        create(1L, "FAVORITE_STYLE", null, "流行");
        create(1L, "EXCLUDE_STYLE", null, "流行");

        var overview = memory.overview(1L);
        var effective = overview.effective();
        assertThat(effective.likeArtists()).isEmpty();
        assertThat(effective.likeStyles()).isEmpty();
        assertThat(effective.excludeArtists()).extracting(cn.edu.seig.vibemusic.model.vo.EffectivePreferenceItemVO::value)
                .containsExactly("测试歌手A");
        assertThat(effective.excludeStyles()).extracting(cn.edu.seig.vibemusic.model.vo.EffectivePreferenceItemVO::value)
                .containsExactly("流行");
        assertThat(overview.conflicts()).hasSize(2);
    }

    @Test
    void autoProfileOnlySupplementsUnspecifiedDimensions() {
        // 用户 1 收藏歌曲 10（风格「流行」，歌手 A）→ 自动画像出现「流行」「测试歌手A」
        jdbc.update("INSERT INTO tb_user_favorite(id,user_id,type,song_id,create_time) "
                + "VALUES (1,1,0,10,CURRENT_TIMESTAMP)");

        var autoOnly = memory.overview(1L);
        assertThat(autoOnly.effective().likeStyles())
                .anySatisfy(item -> assertThat(item.source()).isEqualTo(cn.edu.seig.vibemusic.enumeration.PreferenceSource.AUTO));
        assertThat(autoOnly.effective().likeArtists())
                .anySatisfy(item -> assertThat(item.source()).isEqualTo(cn.edu.seig.vibemusic.enumeration.PreferenceSource.AUTO));

        // 手动喜欢「流行」后，自动项不再重复出现同一风格
        create(1L, "FAVORITE_STYLE", null, "流行");
        var merged = memory.overview(1L);
        assertThat(merged.effective().likeStyles())
                .extracting(cn.edu.seig.vibemusic.model.vo.EffectivePreferenceItemVO::value)
                .containsOnlyOnce("流行");

        // 手动排除「测试歌手B」后，自动补充不得把 B 加回来（即使随后收藏了 B 的歌）
        create(1L, "EXCLUDE_ARTIST", 2L, null);
        jdbc.update("INSERT INTO tb_user_favorite(id,user_id,type,song_id,create_time) "
                + "VALUES (2,1,0,13,CURRENT_TIMESTAMP)");
        assertThat(memory.overview(1L).effective().likeArtists())
                .extracting(cn.edu.seig.vibemusic.model.vo.EffectivePreferenceItemVO::value)
                .doesNotContain("测试歌手B");
    }

    // ------------------------------------------------------------ 隔离性

    @Test
    void manualPreferencesSurviveProfileRefresh() {
        create(1L, "PREFERENCE_NOTE", null, "更喜欢旋律优美的华语歌曲");
        create(1L, "EXCLUDE_STYLE", null, "摇滚");
        jdbc.update("INSERT INTO tb_user_favorite(id,user_id,type,song_id,create_time) "
                + "VALUES (1,1,0,10,CURRENT_TIMESTAMP)");

        // 自动画像刷新（TTL 过期 / 会话结束都会触发）绝不覆盖手动偏好
        profileService.refresh(1L);

        var overview = memory.overview(1L);
        assertThat(overview.preferences()).hasSize(2);
        assertThat(overview.effective().preferenceNotes())
                .extracting(cn.edu.seig.vibemusic.model.vo.EffectivePreferenceItemVO::value)
                .containsExactly("更喜欢旋律优美的华语歌曲");
        assertThat(overview.effective().excludeStyles())
                .extracting(cn.edu.seig.vibemusic.model.vo.EffectivePreferenceItemVO::value)
                .containsExactly("摇滚");
    }

    // ------------------------------------------------------------ 个性化开关

    @Test
    void personalizationOffClearsEffectiveButKeepsPreferencesAndPersists() {
        create(1L, "FAVORITE_STYLE", null, "流行");
        jdbc.update("INSERT INTO tb_user_favorite(id,user_id,type,song_id,create_time) "
                + "VALUES (1,1,0,10,CURRENT_TIMESTAMP)");

        assertThat(memory.setPersonalizationEnabled(1L, false)).isFalse();
        // 开关持久化：重新读取（模拟新请求）仍是关闭
        assertThat(memory.isPersonalizationEnabled(1L)).isFalse();

        var overview = memory.overview(1L);
        assertThat(overview.personalizationEnabled()).isFalse();
        assertThat(overview.preferences()).hasSize(1); // 关闭不等于删除
        assertThat(overview.effective().likeStyles()).isEmpty();
        assertThat(overview.effective().excludeStyles()).isEmpty();

        memory.setPersonalizationEnabled(1L, true);
        assertThat(memory.overview(1L).effective().likeStyles()).isNotEmpty();
    }

    // ------------------------------------------------------------ 清除自动记忆

    @Test
    void clearAutoRemovesProfileAndSummariesButKeepsManualAndBusinessData() {
        create(1L, "FAVORITE_STYLE", null, "流行");
        jdbc.update("INSERT INTO tb_user_favorite(id,user_id,type,song_id,create_time) "
                + "VALUES (1,1,0,10,CURRENT_TIMESTAMP)");
        profileService.read(1L); // 确保画像行存在，clearAuto 应当真的删掉它

        jdbc.update("INSERT INTO ai_chat_state(chat_id, user_id, summary_text, summary_upto_id) "
                + "VALUES ('user_chat_id_1', 1, '用户之前聊过粤语歌手', 5)");
        jdbc.update("INSERT INTO chat_messages(chat_id, message_type, content, created_at, updated_at) "
                + "VALUES ('user_chat_id_1', 'USER', '帮我创建歌单', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO tb_agent_pending_action(task_id, user_id, chat_id, action_type, "
                + "draft_json, preview_json, version, status, created_at, updated_at, expires_at) "
                + "VALUES ('t1', 1, 'user_chat_id_1', 'CREATE_PLAYLIST', '{}', '{}', 1, 'PENDING', "
                + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");

        var result = memory.clearAuto(1L);

        assertThat(result.profileCleared()).isTrue();
        assertThat(result.summariesCleared()).isEqualTo(1);
        // 画像行确实被删除（必须在下一次 overview 之前断言 —— overview 会按 TTL 惰性重建画像，
        // 这是预期行为，前端文案必须向用户说明「哪些信息会从现有来源重新生成」）。
        assertThat(count("tb_user_music_profile")).isZero();
        // 手动偏好保留
        assertThat(memory.overview(1L).preferences()).hasSize(1);
        // 聊天消息、会话行、待确认任务（幂等执行链路）一律不动
        assertThat(count("chat_messages")).isEqualTo(1);
        assertThat(count("ai_chat_state")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT summary_text FROM ai_chat_state WHERE chat_id = 'user_chat_id_1'", String.class)).isNull();
        assertThat(count("tb_agent_pending_action")).isEqualTo(1);
    }

    // ------------------------------------------------------------ 风格选项与提示词

    @Test
    void styleOptionsAreDerivedFromSongLibrary() {
        assertThat(memory.styleOptions()).contains("流行", "摇滚", "民谣", "粤语");
        assertThat(memory.styleOptions()).doesNotContain("不存在的风格");
    }

    @Test
    void deletedPreferenceStopsBeingInjectedIntoPrompt() {
        create(1L, "PREFERENCE_NOTE", null, "优先推荐冷门作品");

        assertThat(memory.renderManualPreferenceBlock(1L)).contains("优先推荐冷门作品");

        Long id = memory.overview(1L).preferences().get(0).getId();
        memory.delete(1L, id);

        // 删除后不再注入（无偏好 → null → 调用方跳过该块）
        assertThat(memory.renderManualPreferenceBlock(1L)).isNull();
    }
}
