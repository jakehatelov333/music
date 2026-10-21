-- MySQL 8.0; repeatable (safe to run more than once). Back up the database before deploying.
--
-- Phase 12 (play history) adds:
--   * tb_play_history : one row per (user, song), holding the most recent effective
--                       playback time, the cumulative count of effective plays and the
--                       progress of the last effective play.
--   * tb_user_music_profile.played_song_count / recent_play_artists :
--                       implicit listening signals for the AI profile. They are stored
--                       SEPARATELY from top_styles / top_artists on purpose: playing a
--                       song is not the same statement as favouriting it or putting it in
--                       a playlist the user built, so the two must never be merged into
--                       the same field.
--
-- Why a single row per (user, song) instead of an append-only event log:
--   * The product surface is "recently played" + "how many times", not a timeline;
--     an event log would need its own retention policy and pagination by event, and the
--     unique key makes repeated playback an UPDATE instead of an unbounded INSERT.
--   * `uk_play_history_user_song` is what makes the write idempotent under concurrency:
--     a duplicate request turns into the same row rather than a second row.
--
-- Guest handling: this table only ever holds rows for authenticated users. Guests keep
-- their queue in the browser (localStorage) and never write here, so two different people
-- on the same machine can never end up sharing a history row.
--
-- Deleted songs: `song_id` is ON DELETE CASCADE. A history row without its song is not
-- displayable (no title, no cover, no playable URL), so keeping it would only produce
-- orphans that inflate the count and break the join. The read queries still INNER JOIN
-- tb_song, so even a row that somehow survived (FK disabled, restored dump) is never
-- returned to the client.
--
-- No credentials are stored here. Only the owner can read or delete a row
-- (every statement filters on user_id from the server-side auth context).
--
-- Rollback (run manually if this phase must be reverted; both changes are additive):
--   DROP TABLE IF EXISTS tb_play_history;
--   ALTER TABLE tb_user_music_profile DROP COLUMN played_song_count;
--   ALTER TABLE tb_user_music_profile DROP COLUMN recent_play_artists;

CREATE TABLE IF NOT EXISTS tb_play_history (
    id                    BIGINT      NOT NULL AUTO_INCREMENT COMMENT '历史记录 id',
    user_id               BIGINT      NOT NULL COMMENT '用户 id（来自服务端认证上下文）',
    song_id               BIGINT      NOT NULL COMMENT '歌曲 id',
    last_played_at        DATETIME(6) NOT NULL COMMENT '最近一次有效播放时间',
    play_count            INT         NOT NULL DEFAULT 1 COMMENT '累计有效播放次数',
    last_position_seconds INT         NULL COMMENT '最近一次有效播放时的进度（秒）',
    last_play_id          VARCHAR(64) NULL COMMENT '最近一次有效播放的幂等键，用于重试/重复事件去重',
    PRIMARY KEY (id),
    -- 去重 + 幂等：同一用户同一首歌永远只有一行，重复播放走 UPDATE 而不是新增行。
    UNIQUE KEY uk_play_history_user_song (user_id, song_id),
    -- 列表查询固定按 (user_id, last_played_at DESC) 取页，索引顺序与之对齐，避免 filesort。
    KEY idx_play_history_user_time (user_id, last_played_at),
    CONSTRAINT fk_play_history_user FOREIGN KEY (user_id) REFERENCES tb_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_play_history_song FOREIGN KEY (song_id) REFERENCES tb_song (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户播放历史（每个用户每首歌一行）';

-- Idempotent column addition for databases that already ran phase5_personalization.sql.
-- Deliberately a separate procedure from the table above so re-running the file is safe.
DROP PROCEDURE IF EXISTS ensure_phase12_profile_play_columns;
DELIMITER $$
CREATE PROCEDURE ensure_phase12_profile_play_columns()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'tb_user_music_profile' AND column_name = 'played_song_count') THEN
        ALTER TABLE tb_user_music_profile ADD COLUMN played_song_count INT NOT NULL DEFAULT 0
            COMMENT '有过有效播放的不同歌曲数（隐式信号，与收藏计数分开）';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'tb_user_music_profile' AND column_name = 'recent_play_artists') THEN
        ALTER TABLE tb_user_music_profile ADD COLUMN recent_play_artists TEXT NULL
            COMMENT '反复播放的歌手 TOP N（隐式信号，JSON 数组文本；不写入 top_artists）';
    END IF;
END$$
DELIMITER ;
CALL ensure_phase12_profile_play_columns();
DROP PROCEDURE IF EXISTS ensure_phase12_profile_play_columns;

-- Postflight: the table must exist and start empty on a fresh deployment.
SELECT table_name, table_rows FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name = 'tb_play_history';
