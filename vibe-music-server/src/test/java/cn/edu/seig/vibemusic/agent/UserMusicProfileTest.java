package cn.edu.seig.vibemusic.agent;

import cn.edu.seig.vibemusic.mapper.UserMusicProfileMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 画像事实聚合的正确性：只验证 SQL 与数据来源，不涉及模型。
 *
 * <p>夹具刻意包含"既被收藏、又在自建歌单里"的重复歌曲（song 10），
 * 用来证明两个来源合并后确实去重，而不是简单相加。
 */
class UserMusicProfileTest extends AbstractAgentTest {

    @Autowired UserMusicProfileMapper profiles;

    private void seedFavoritesAndPlaylist() {
        // 收藏 3 首：10(流行/歌手A)、11(流行/歌手A)、13(民谣/歌手B)
        jdbc.update("INSERT INTO tb_user_favorite(id,user_id,type,song_id,create_time) VALUES "
                + "(1,1,0,10,CURRENT_TIMESTAMP),(2,1,0,11,CURRENT_TIMESTAMP),(3,1,0,13,CURRENT_TIMESTAMP)");
        // 收藏 1 个别人（user 2）的歌单
        jdbc.update("INSERT INTO tb_playlist(id,title,owner_user_id,is_public,status,cover_is_custom) "
                + "VALUES (900,'别人的歌单',2,TRUE,'PUBLISHED',FALSE)");
        jdbc.update("INSERT INTO tb_user_favorite(id,user_id,type,playlist_id,create_time) "
                + "VALUES (4,1,1,900,CURRENT_TIMESTAMP)");
        // 自建歌单，含 10（与收藏重复，必须去重）与 14(粤语/歌手B)
        jdbc.update("INSERT INTO tb_playlist(id,title,owner_user_id,is_public,status,cover_is_custom) "
                + "VALUES (901,'我的夜跑歌单',1,TRUE,'PUBLISHED',FALSE)");
        jdbc.update("INSERT INTO tb_playlist_binding(playlist_id,song_id) VALUES (901,10),(901,14)");
    }

    @Test
    void countsFavoritesAndOwnPlaylistSongs() {
        seedFavoritesAndPlaylist();

        var counts = profiles.aggregateCounts(1L);

        assertThat(counts.getFavSongCount()).isEqualTo(3);
        assertThat(counts.getFavPlaylistCount()).isEqualTo(1);
        // 歌单 900 属于 user 2，不能算进 user 1 的自建歌单
        assertThat(counts.getOwnPlaylistCount()).isEqualTo(1);
        // 收藏 10/11/13 + 自建歌单 10/14 -> 去重后 4 首
        assertThat(counts.getOwnPlaylistSongCount()).isEqualTo(4);
    }

    @Test
    void ranksStylesByFrequencyAndDeduplicatesAcrossSources() {
        seedFavoritesAndPlaylist();

        var styles = profiles.topStyles(1L, 5);

        assertThat(styles).extracting("name")
                .containsExactlyInAnyOrder("流行", "民谣", "粤语");
        // 10、11 都是流行；10 在收藏与歌单里各出现一次，但只应计一次
        assertThat(styles.get(0).getName()).isEqualTo("流行");
        assertThat(styles.get(0).getCount()).isEqualTo(2);
    }

    @Test
    void ranksArtistsAndSkipsSongsWithoutArtist() {
        seedFavoritesAndPlaylist();

        var artists = profiles.topArtists(1L, 5);

        assertThat(artists).extracting("name").containsExactly("测试歌手A", "测试歌手B");
        assertThat(artists.get(0).getCount()).isEqualTo(2);
        assertThat(artists.get(0).getId()).isEqualTo(1L);
    }

    @Test
    void upsertKeepsSingleRowAndBumpsVersion() {
        seedFavoritesAndPlaylist();
        var facts = profiles.aggregateCounts(1L);
        facts.setUserId(1L);
        facts.setTopStyles("[]");
        facts.setTopArtists("[]");

        profiles.upsertFacts(facts, LocalDateTime.now());
        profiles.upsertFacts(facts, LocalDateTime.now());

        assertThat(count("tb_user_music_profile")).isEqualTo(1);
        var stored = profiles.find(1L);
        assertThat(stored.getVersion()).isEqualTo(2);
        assertThat(stored.getFavSongCount()).isEqualTo(3);
        assertThat(stored.getOwnPlaylistSongCount()).isEqualTo(4);
    }

    @Test
    void upsertDoesNotClobberAnExistingNaturalLanguageSummary() {
        seedFavoritesAndPlaylist();
        var facts = profiles.aggregateCounts(1L);
        facts.setUserId(1L);
        facts.setTopStyles("[]");
        facts.setTopArtists("[]");
        profiles.upsertFacts(facts, LocalDateTime.now());
        jdbc.update("UPDATE tb_user_music_profile SET pref_summary = '偏爱流行与民谣', "
                + "summary_source_hash = 'abc' WHERE user_id = 1");

        profiles.upsertFacts(facts, LocalDateTime.now());

        var stored = profiles.find(1L);
        assertThat(stored.getPrefSummary()).isEqualTo("偏爱流行与民谣");
        assertThat(stored.getSummarySourceHash()).isEqualTo("abc");
    }
}
