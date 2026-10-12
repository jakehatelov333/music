-- MySQL 8.0; repeatable (safe to run more than once). Back up the database before deploying.
--
-- Phase 5 (AI personalization + summary memory) adds:
--   * tb_user_music_profile : aggregated music taste per user, derived ONLY from
--                             tb_user_favorite and the user's own playlists.
--                             There is no play-history table yet, so nothing here
--                             pretends to know what the user listened to.
--   * ai_chat_state.summary_text / summary_upto_id / summary_updated_at :
--                             a rolling compression of the messages that already fell
--                             out of the 20-message model window.
--
-- Neither stores credentials. summary_text may contain conversation content, so it is
-- cleared together with the conversation (see IAiService.deleteMessagesByChatId).

CREATE TABLE IF NOT EXISTS tb_user_music_profile (
    user_id                 BIGINT       NOT NULL COMMENT '用户 id',
    top_styles              TEXT         NULL COMMENT '高频风格 TOP N，JSON 数组文本',
    top_artists             TEXT         NULL COMMENT '高频歌手 TOP N，JSON 数组文本',
    fav_song_count          INT          NOT NULL DEFAULT 0 COMMENT '收藏歌曲数',
    fav_playlist_count      INT          NOT NULL DEFAULT 0 COMMENT '收藏歌单数',
    own_playlist_count      INT          NOT NULL DEFAULT 0 COMMENT '自建歌单数',
    own_playlist_song_count INT          NOT NULL DEFAULT 0 COMMENT '自建歌单内歌曲数（去重）',
    pref_summary            VARCHAR(500) NULL COMMENT 'qwen-flash 生成的偏好概述，可为空',
    summary_source_hash     CHAR(64)     NULL COMMENT '生成 pref_summary 时的事实指纹（SHA-256）',
    version                 BIGINT       NOT NULL DEFAULT 0 COMMENT '每次事实刷新 +1',
    updated_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                         ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_music_profile_user FOREIGN KEY (user_id) REFERENCES tb_user (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户音乐画像（由收藏与自建歌单聚合）';

-- Idempotent column addition for databases that already ran an earlier revision.
DROP PROCEDURE IF EXISTS ensure_phase5_summary_columns;
DELIMITER $$
CREATE PROCEDURE ensure_phase5_summary_columns()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_chat_state' AND column_name = 'summary_text') THEN
        ALTER TABLE ai_chat_state ADD COLUMN summary_text LONGTEXT NULL
            COMMENT '更早对话的滚动摘要（不含系统提示词）';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_chat_state' AND column_name = 'summary_upto_id') THEN
        ALTER TABLE ai_chat_state ADD COLUMN summary_upto_id BIGINT NOT NULL DEFAULT 0
            COMMENT '摘要已覆盖到 chat_messages.id 的这个值（含）';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_chat_state' AND column_name = 'summary_updated_at') THEN
        ALTER TABLE ai_chat_state ADD COLUMN summary_updated_at DATETIME(6) NULL;
    END IF;
END$$
DELIMITER ;
CALL ensure_phase5_summary_columns();
DROP PROCEDURE IF EXISTS ensure_phase5_summary_columns;

-- Postflight: the profile table must exist and start empty on a fresh deployment.
SELECT table_name, table_rows FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name = 'tb_user_music_profile';
