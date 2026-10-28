-- Phase 15: feedback read status.
-- MySQL 8.0; repeatable (safe to run more than once). Back up the database before deploying.
--
-- 背景：管理端反馈列表需要区分「已查看 / 未查看」，并支持单条或批量标记已查看。
-- 历史反馈默认按未查看处理（is_read = 0），便于管理员先处理积压反馈。

SET @ddl := (
    SELECT IF(
        EXISTS(
            SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'tb_feedback'
              AND COLUMN_NAME = 'is_read'
        ),
        'SELECT ''tb_feedback.is_read already exists, skip'' AS note',
        'ALTER TABLE tb_feedback ADD COLUMN is_read TINYINT NOT NULL DEFAULT 0 COMMENT ''查看状态：0-未查看，1-已查看'' AFTER feedback'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := (
    SELECT IF(
        EXISTS(
            SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'tb_feedback'
              AND COLUMN_NAME = 'read_time'
        ),
        'SELECT ''tb_feedback.read_time already exists, skip'' AS note',
        'ALTER TABLE tb_feedback ADD COLUMN read_time DATETIME NULL COMMENT ''查看时间'' AFTER is_read'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 历史已查看记录没有精确时间，以迁移执行时间作为首次可得时间回填。
UPDATE tb_feedback
SET read_time = NOW()
WHERE is_read = 1
  AND read_time IS NULL;

SET @ddl := (
    SELECT IF(
        EXISTS(
            SELECT 1 FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'tb_feedback'
              AND INDEX_NAME = 'idx_feedback_is_read_create_time'
        ),
        'SELECT ''idx_feedback_is_read_create_time already exists, skip'' AS note',
        'ALTER TABLE tb_feedback ADD INDEX idx_feedback_is_read_create_time (is_read, create_time)'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 回滚（仅在确认不再需要查看状态时执行）：
--   ALTER TABLE tb_feedback DROP INDEX idx_feedback_is_read_create_time;
--   ALTER TABLE tb_feedback DROP COLUMN read_time;
--   ALTER TABLE tb_feedback DROP COLUMN is_read;
