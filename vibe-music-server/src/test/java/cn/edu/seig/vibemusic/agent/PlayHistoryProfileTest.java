package cn.edu.seig.vibemusic.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 播放历史作为<b>隐式信号</b>接入画像的边界。
 *
 * <p>这里要钉住的不是「能不能统计」，而是几条产品红线：
 * <ol>
 *     <li>播放记录<b>不能</b>写进 {@code top_artists}（那是用户显式收藏/建歌单产生的字段）；</li>
 *     <li>只播过一次的歌手<b>不能</b>进入偏好（单次播放不等于长期偏好）；</li>
 *     <li>播放行为<b>不能</b>覆盖用户手动设置的偏好（手动层优先级最高）；</li>
 *     <li>注入提示词时必须明确标注「隐式、仅弱参考」，避免模型把它说成「喜欢」。</li>
 * </ol>
 *
 * <p>夹具沿用 {@link AbstractAgentTest}：用户 1（alice）、用户 2（bob），
 * 歌曲 10/11/12 属于歌手 A（id=1），13/14 属于歌手 B（id=2）。
 */
class PlayHistoryProfileTest extends AbstractAgentTest {

    /** 直接写历史表：画像聚合只读表，不需要走 Service 的认证链路。 */
    private void play(long userId, long songId, int count) {
        jdbc.update("INSERT INTO tb_play_history(user_id, song_id, last_played_at, play_count) "
                + "VALUES (?, ?, CURRENT_TIMESTAMP, ?)", userId, songId, count);
    }

    /**
     * 反复播放某位歌手的多首歌 -> 进入「反复播放的歌手」，但<b>不会</b>进入 top_artists。
     */
    @Test
    void repeatedlyPlayedArtistsAreRecordedSeparatelyFromExplicitTaste() {
        play(1L, 10L, 3);   // 歌手A
        play(1L, 11L, 2);   // 歌手A（第二首）
        play(1L, 12L, 1);   // 歌手A（第三首）
        play(1L, 13L, 1);   // 歌手B（只有一首）

        var profile = profileService.refresh(1L);

        assertThat(profile.getPlayedSongCount()).isEqualTo(4);
        // 隐式信号：歌手A 有 3 首不同的歌被听过
        assertThat(profile.getRecentPlayArtists()).contains("测试歌手A");
        // 歌手B 只有 1 首，未达「至少 2 首不同歌曲」的门槛
        assertThat(profile.getRecentPlayArtists()).doesNotContain("测试歌手B");
        // 关键：显式偏好字段不受播放影响（用户没有任何收藏/自建歌单）
        assertThat(profile.getTopArtists()).isEqualTo("[]");
        assertThat(profile.getTopStyles()).isEqualTo("[]");
        assertThat(profile.getFavSongCount()).isZero();
    }

    /**
     * 单曲循环不能把某位歌手顶成「偏好」：即使 play_count 很大，
     * 只要只有一首不同的歌，就不进画像。
     */
    @Test
    void singleSongOnRepeatDoesNotBecomeAPreference() {
        play(1L, 10L, 500); // 同一首歌循环 500 次

        var profile = profileService.refresh(1L);

        assertThat(profile.getPlayedSongCount()).isEqualTo(1);
        assertThat(profile.getRecentPlayArtists()).isEqualTo("[]");
        assertThat(profile.getTopArtists()).isEqualTo("[]");
    }

    /** 没有播放记录时，画像行为与改造前完全一致（回归保护）。 */
    @Test
    void profileWithoutPlayHistoryBehavesAsBefore() {
        jdbc.update("INSERT INTO tb_user_favorite(id,user_id,type,song_id,create_time) "
                + "VALUES (1,1,0,10,CURRENT_TIMESTAMP)");

        var profile = profileService.refresh(1L);

        assertThat(profile.getPlayedSongCount()).isZero();
        assertThat(profile.getRecentPlayArtists()).isEqualTo("[]");
        assertThat(profile.getTopArtists()).contains("测试歌手A");
        assertThat(profile.getTopStyles()).contains("流行");
    }

    /**
     * 注入提示词时，隐式信号必须带「弱参考」措辞，并与显式信号分开表述。
     * 这是防止模型把「听过」说成「喜欢」的最后一道文字防线。
     */
    @Test
    void preferenceBlockLabelsPlaybackAsWeakSignal() {
        play(1L, 10L, 2);
        play(1L, 11L, 2);

        String block = profileService.renderPreferenceBlock(1L);

        assertThat(block).contains("播放记录（隐式信号");
        assertThat(block).contains("不能据此断定用户喜欢什么");
        assertThat(block).contains("反复播放的歌手").contains("测试歌手A");
        // 隐式信号不能被表述成「常听歌手」—— 那是显式信号的措辞
        assertThat(block).doesNotContain("常听歌手");
    }

    /** 只有播放记录、没有任何收藏时，仍然注入（否则新用户的播放行为完全用不上）。 */
    @Test
    void playOnlyUserStillGetsAPlaybackBlock() {
        play(1L, 10L, 2);

        String block = profileService.renderPreferenceBlock(1L);

        assertThat(block).isNotNull().contains("播放过 1 首不同的歌");
        // 没有显式数据时不能凭空出现「常听风格 / 常听歌手」
        assertThat(block).doesNotContain("常听风格").doesNotContain("常听歌手");
    }

    /**
     * 手动偏好优先级最高：用户显式排除的歌手，不能因为「播放得多」而被画像重新加回。
     * 这是任务里「不要覆盖用户明确设置的偏好」的直接断言。
     */
    @Test
    void playbackNeverOverridesAnExplicitManualExclusion() {
        play(1L, 10L, 3);
        play(1L, 11L, 3);
        // 用户显式排除歌手A（id=1）
        var request = new cn.edu.seig.vibemusic.model.dto.MemoryPreferenceRequest();
        request.setCategory("EXCLUDE_ARTIST");
        request.setTargetId(1L);
        request.setTargetValue("测试歌手A");
        memoryPreferenceService.create(1L, request);

        var effective = memoryPreferenceService.overview(1L).effective();

        // 排除项里必须有歌手A
        assertThat(effective.excludeArtists())
                .extracting(cn.edu.seig.vibemusic.model.vo.EffectivePreferenceItemVO::value)
                .contains("测试歌手A");
        // 「喜欢歌手」里不能出现歌手A —— 播放再多也不能推翻用户的显式排除
        assertThat(effective.likeArtists())
                .extracting(cn.edu.seig.vibemusic.model.vo.EffectivePreferenceItemVO::value)
                .doesNotContain("测试歌手A");
        // 结构化排除约束同样生效（检索链路会真的过滤掉）
        assertThat(memoryPreferenceService.exclusionConstraints(1L).excludedArtistIds())
                .contains(1L);
    }

    /** 不同用户的播放历史互不干扰（画像聚合也带 user_id 条件）。 */
    @Test
    void playHistoryOfOneUserNeverLeaksIntoAnotherProfile() {
        play(1L, 10L, 2);
        play(1L, 11L, 2);

        var bob = profileService.refresh(2L);

        assertThat(bob.getPlayedSongCount()).isZero();
        assertThat(bob.getRecentPlayArtists()).isEqualTo("[]");
    }

    /** 播放记录变化后，事实指纹必须变 —— 否则偏好概述会一直停在旧版本。 */
    @Test
    void playHistoryChangesTheFactsHash() {
        play(1L, 10L, 2);
        String before = cn.edu.seig.vibemusic.service.agent.UserMusicProfileService
                .factsHash(profileService.refresh(1L));

        play(1L, 11L, 2);
        String after = cn.edu.seig.vibemusic.service.agent.UserMusicProfileService
                .factsHash(profileService.refresh(1L));

        assertThat(after).isNotEqualTo(before).hasSize(64);
    }

    /** 歌曲被删除后，其历史随之消失，画像里的统计也要同步下降。 */
    @Test
    void deletingASongShrinksPlaybackFacts() {
        play(1L, 10L, 2);
        play(1L, 11L, 2);
        assertThat(profileService.refresh(1L).getPlayedSongCount()).isEqualTo(2);

        jdbc.update("DELETE FROM tb_song_artist WHERE song_id = 11");
        jdbc.update("DELETE FROM tb_song WHERE id = 11");

        var profile = profileService.refresh(1L);
        assertThat(profile.getPlayedSongCount()).isEqualTo(1);
        // 只剩一首歌 -> 未达门槛，歌手不再被算作「反复播放」
        assertThat(profile.getRecentPlayArtists()).isEqualTo("[]");
    }
}
