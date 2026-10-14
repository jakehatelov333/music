-- MySQL 8.0; repeatable (safe to run more than once). Back up the database before deploying.
--
-- Phase 7 (multi-conversation) turns ai_chat_state from a pure runtime-state table into a
-- conversation table that carries ownership:
--
--   * user_id        : who owns the conversation. NULL = guest conversation.
--   * title          : taken from the FIRST user message, truncated. No model call.
--   * created_at     : when the conversation first existed.
--   * last_active_at : when it last produced messages. Drives the "recent conversations" list.
--
-- Why the ownership column is mandatory rather than a naming convention:
--   Before this phase a user's chat_id was literally "user_chat_id_{userId}", so ownership was
--   implied by the string prefix. That is not a usable filter for "list my conversations" --
--   LIKE 'user_chat_id_12%' also matches user_chat_id_123, i.e. it would leak another user's
--   conversation list. Ownership has to be a real, indexed column compared for equality.
--
-- Note on the deliberate omission of a foreign key on user_id:
--   ai_chat_state is a runtime table holding leases and a 255-char binary-collated key. Coupling
--   its lifecycle to tb_user deletion is out of scope for this phase and would require cleaning
--   pre-existing orphans first. The backfill below therefore skips rows whose user no longer
--   exists instead of failing the whole migration.
--
-- Historical data is preserved: no row is deleted, no chat_id is rewritten.

-- ---------------------------------------------------------------- 1. columns

DROP PROCEDURE IF EXISTS ensure_phase7_conversation_columns;
DELIMITER $$
CREATE PROCEDURE ensure_phase7_conversation_columns()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_chat_state' AND column_name = 'user_id') THEN
        ALTER TABLE ai_chat_state ADD COLUMN user_id BIGINT NULL
            COMMENT '会话归属用户；游客会话为 NULL';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_chat_state' AND column_name = 'title') THEN
        ALTER TABLE ai_chat_state ADD COLUMN title VARCHAR(64) NULL
            COMMENT '会话标题，取自首条用户消息的截断，不调用模型';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_chat_state' AND column_name = 'created_at') THEN
        ALTER TABLE ai_chat_state ADD COLUMN created_at DATETIME(6) NULL
            COMMENT '会话创建时间';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'ai_chat_state' AND column_name = 'last_active_at') THEN
        ALTER TABLE ai_chat_state ADD COLUMN last_active_at DATETIME(6) NULL
            COMMENT '最近一次产生消息的时间，用于会话列表倒序';
    END IF;
END$$
DELIMITER ;
CALL ensure_phase7_conversation_columns();
DROP PROCEDURE IF EXISTS ensure_phase7_conversation_columns;

-- ---------------------------------------------------------------- 2. index

-- Ascending on purpose: MySQL 8 scans it backwards for DESC, and an ascending index keeps the
-- script portable to older 5.7-era deployments.
SET @phase7_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ai_chat_state'
      AND index_name = 'idx_ai_chat_state_user_active'
);
SET @phase7_index_sql = IF(@phase7_index_exists > 0, 'SELECT 1',
    'ALTER TABLE ai_chat_state ADD INDEX idx_ai_chat_state_user_active (user_id, last_active_at)');
PREPARE phase7_index_stmt FROM @phase7_index_sql;
EXECUTE phase7_index_stmt;
DEALLOCATE PREPARE phase7_index_stmt;

-- ---------------------------------------------------------------- 3. backfill ownership

-- Legacy logged-in conversations are exactly "user_chat_id_{digits}" (13-char prefix).
-- The EXISTS guard drops rows whose owner was deleted, so the column never points at a ghost.
UPDATE ai_chat_state s
SET s.user_id = CAST(SUBSTRING(s.chat_id, 14) AS UNSIGNED)
WHERE s.user_id IS NULL
  AND s.chat_id REGEXP '^user_chat_id_[0-9]+$'
  AND EXISTS (SELECT 1 FROM tb_user u WHERE u.id = CAST(SUBSTRING(s.chat_id, 14) AS UNSIGNED));

-- guest_chat_v2_* intentionally keeps user_id = NULL: guest conversations are scoped by the
-- short-lived cookie credential, not by an account.

-- ---------------------------------------------------------------- 4. backfill title

-- Gives every existing conversation a title immediately, so the list is not full of blanks
-- before the user sends anything new. Same rule the runtime uses: first user message, truncated.
UPDATE ai_chat_state s
SET s.title = (
        SELECT LEFT(m.content, 60) FROM chat_messages m
        WHERE m.chat_id = s.chat_id AND m.message_type = 'USER'
        ORDER BY m.id ASC LIMIT 1)
WHERE s.title IS NULL
  AND EXISTS (SELECT 1 FROM chat_messages m WHERE m.chat_id = s.chat_id AND m.message_type = 'USER');

-- ---------------------------------------------------------------- 5. backfill timestamps

UPDATE ai_chat_state s
SET s.last_active_at = COALESCE(
        (SELECT MAX(m.created_at) FROM chat_messages m WHERE m.chat_id = s.chat_id),
        s.last_active_at)
WHERE s.last_active_at IS NULL;

UPDATE ai_chat_state s
SET s.created_at = COALESCE(
        (SELECT MIN(m.created_at) FROM chat_messages m WHERE m.chat_id = s.chat_id),
        s.last_active_at,
        CURRENT_TIMESTAMP(6))
WHERE s.created_at IS NULL;

-- ---------------------------------------------------------------- postflight

-- Must report one row per column; user_id must be filled for every "user_chat_id_*" row that
-- still has a live owner, and must stay NULL for guest rows.
SELECT column_name, column_type, is_nullable
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'ai_chat_state'
  AND column_name IN ('user_id', 'title', 'created_at', 'last_active_at')
ORDER BY column_name;

SELECT
    SUM(chat_id LIKE 'user\_chat\_id\_%')                       AS legacy_user_rows,
    SUM(user_id IS NOT NULL)                                    AS owned_rows,
    SUM(chat_id LIKE 'guest\_chat\_v2\_%' AND user_id IS NOT NULL) AS guest_rows_wrongly_owned,
    SUM(title IS NOT NULL)                                      AS titled_rows
FROM ai_chat_state;
