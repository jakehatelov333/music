-- MySQL 8.0; repeatable (safe to run more than once). Back up the database before deploying.
--
-- Phase 8 (AI memory & manual preferences) adds:
--   * tb_user_memory_preference : preferences the USER explicitly sets (likes / excludes /
--                                 free-text notes). This is an independent layer from the
--                                 auto-aggregated tb_user_music_profile and must never be
--                                 overwritten by profile refreshes.
--   * tb_user_ai_memory_setting : per-user personalization switch. A missing row means the
--                                 switch is ON (personalization_enabled defaults to TRUE).
--
-- Neither table stores credentials. Free-text notes may contain user content, so they are
-- returned only to their owner (WHERE id = ? AND user_id = ?) and are cleared on user delete
-- via ON DELETE CASCADE.
--
-- Rollback (run manually if this phase must be reverted; no data migration needed because
-- both tables are additive and nothing else references them):
--   DROP TABLE IF EXISTS tb_user_memory_preference;
--   DROP TABLE IF EXISTS tb_user_ai_memory_setting;

CREATE TABLE IF NOT EXISTS tb_user_memory_preference (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT       NOT NULL COMMENT '归属用户 id',
    category     VARCHAR(32)  NOT NULL COMMENT 'FAVORITE_ARTIST/FAVORITE_STYLE/PREFERENCE_NOTE/EXCLUDE_ARTIST/EXCLUDE_STYLE/EXCLUDE_NOTE',
    target_id    BIGINT       NULL     COMMENT '结构化目标 id：歌手=artist_id；风格/自由文本=NULL',
    target_value VARCHAR(255) NOT NULL COMMENT '规范化文本：歌手名/风格名/自由文本',
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    UNIQUE KEY uk_user_memory_pref (user_id, category, target_value),
    KEY idx_user_memory_pref_user (user_id),
    CONSTRAINT fk_user_memory_pref_user FOREIGN KEY (user_id) REFERENCES tb_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户手动指定的 AI 偏好（独立于自动画像）';

CREATE TABLE IF NOT EXISTS tb_user_ai_memory_setting (
    user_id                 BIGINT      NOT NULL PRIMARY KEY,
    personalization_enabled BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at              DATETIME(6) NOT NULL,
    CONSTRAINT fk_user_ai_memory_setting_user FOREIGN KEY (user_id) REFERENCES tb_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 记忆个性化开关';

-- Postflight: both tables must exist on a fresh deployment. tb_user_memory_preference should
-- start empty; tb_user_ai_memory_setting may be empty (no row == personalization ON).
SELECT table_name, table_rows FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('tb_user_memory_preference', 'tb_user_ai_memory_setting')
ORDER BY table_name;
