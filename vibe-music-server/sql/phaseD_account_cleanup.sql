-- MySQL 8.0; repeatable (safe to run more than once). Back up the database before deploying.
--
-- Phase D (unified account deletion) — schema additions.
--
-- 背景：管理员删除账号历史上只有一句 `DELETE FROM tb_user`，导致
--   * 有反馈的账号被 tb_feedback 的 ON DELETE RESTRICT 直接拒绝，删不掉；
--   * ai_chat_state / chat_messages / tb_ai_task_metric 无外键，留下孤儿数据；
--   * 头像与个人歌单的自定义封面没有任何清理，文件永久残留。
-- 现在单删 / 批删 / 自助注销统一走 AccountDeletionService。
--
-- 本迁移做两件事：
--   1. 让 tb_account_cleanup_task 的唯一键能容纳「同一用户的多个资源」；
--   2. 把 source_type 的取值域写进注释，避免代码与文档漂移。
--
-- 关于封面：个人歌单自定义封面<b>不走</b>这张表，而是登记到 tb_media_cleanup_task
-- （source_type = ACCOUNT_DELETE）。原因：封面可能与其他记录共享，必须由
-- MediaCleanupService 的跨表引用核对决定能不能删，不能"账号没了就删文件"。
-- 因此本表仍然只承载 REDIS_TOKEN 这类无法核对引用的资源。

-- ---------------------------------------------------------------------------
-- 1) 唯一键改为 (user_id, resource_type, resource_ref)
--    原唯一键 (user_id, resource_type) 在"同一用户多个同类资源"时会互相覆盖。
--    加 resource_ref 后每个具体资源各占一行；MySQL 唯一索引允许 NULL 重复，
--    所以 resource_ref 为 NULL 的历史行不会互相冲突。
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS ensure_phaseD_cleanup_keys;
DELIMITER $$
CREATE PROCEDURE ensure_phaseD_cleanup_keys()
BEGIN
    -- 旧唯一键（(user_id, resource_type)）存在则删除
    IF EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'tb_account_cleanup_task'
          AND index_name = 'uk_account_cleanup'
          AND seq_in_index = 2
          AND column_name = 'resource_type'
          AND NOT EXISTS (
              SELECT 1 FROM information_schema.statistics s2
              WHERE s2.table_schema = DATABASE()
                AND s2.table_name = 'tb_account_cleanup_task'
                AND s2.index_name = 'uk_account_cleanup'
                AND s2.seq_in_index = 3
          )
    ) THEN
        ALTER TABLE tb_account_cleanup_task DROP INDEX uk_account_cleanup;
    END IF;

    -- 新唯一键 (user_id, resource_type, resource_ref)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'tb_account_cleanup_task'
          AND index_name = 'uk_account_cleanup'
          AND seq_in_index = 3
    ) THEN
        -- resource_ref 是 VARCHAR(512)；utf8mb4 下 512*4=2048 字节，超过 InnoDB
        -- 3072 字节的索引上限还算安全，但为了不触发"索引过长"，只取前 255 字符。
        ALTER TABLE tb_account_cleanup_task
            ADD UNIQUE KEY uk_account_cleanup (user_id, resource_type, resource_ref(255));
    END IF;
END$$
DELIMITER ;
CALL ensure_phaseD_cleanup_keys();
DROP PROCEDURE IF EXISTS ensure_phaseD_cleanup_keys;

-- ---------------------------------------------------------------------------
-- 2) resource_type 取值域注释（仅文档用途；COLUMN 注释不参与约束）
--    当前只有 REDIS_TOKEN。封面走 tb_media_cleanup_task。
-- ---------------------------------------------------------------------------
ALTER TABLE tb_account_cleanup_task
    MODIFY COLUMN resource_type VARCHAR(32) NOT NULL
        COMMENT '待清理资源类型：REDIS_TOKEN（封面请走 tb_media_cleanup_task）';

-- ---------------------------------------------------------------------------
-- 3) 查询索引：AccountCleanupRetryTask 每次按 (status, attempts, id) 取一批
--    （phase10 已建，这里幂等补一次，供只跑过旧版本的库使用）
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS ensure_phaseD_retry_index;
DELIMITER $$
CREATE PROCEDURE ensure_phaseD_retry_index()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'tb_account_cleanup_task'
          AND index_name = 'idx_account_cleanup_pending'
    ) THEN
        ALTER TABLE tb_account_cleanup_task
            ADD KEY idx_account_cleanup_pending (status, attempts, id);
    END IF;
END$$
DELIMITER ;
CALL ensure_phaseD_retry_index();
DROP PROCEDURE IF EXISTS ensure_phaseD_retry_index;

-- ---------------------------------------------------------------------------
-- Postflight：确认唯一键列顺序符合预期（应是 user_id, resource_type, resource_ref）
-- ---------------------------------------------------------------------------
SELECT index_name, seq_in_index, column_name, non_unique
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'tb_account_cleanup_task'
ORDER BY index_name, seq_in_index;
