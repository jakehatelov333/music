-- MySQL 8.0; repeatable (safe to run more than once). Back up the database before deploying.
--
-- Phase 10 (account deletion reliability) adds one table:
--   * tb_account_cleanup_task : durable retry queue for EXTERNAL resources that must be cleaned
--                               AFTER the account row is committed as deleted.
--
-- Why it exists: deleting an account touches MySQL (user + cascades) plus two external systems
-- (Redis token, MinIO avatar). The external systems do NOT participate in the MySQL transaction,
-- so if their cleanup fails we cannot roll the (already committed) deletion back. Instead we
-- record a pending task and let a scheduled job retry it.
--
-- No foreign key to tb_user on purpose: the owning user row is already gone when the task is
-- written. resource_ref holds the token string (REDIS_TOKEN) or the avatar URL (MINIO_AVATAR).
-- Neither column stores credentials beyond the short-lived token that is being revoked.

CREATE TABLE IF NOT EXISTS tb_account_cleanup_task (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL COMMENT '已注销用户 id（该用户行已删除，故不设外键）',
    resource_type VARCHAR(32)  NOT NULL COMMENT 'REDIS_TOKEN / MINIO_AVATAR',
    resource_ref  VARCHAR(512) NULL     COMMENT '待清理资源引用：token 串或头像 URL',
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / DONE / FAILED',
    attempts      INT          NOT NULL DEFAULT 0 COMMENT '已尝试次数',
    last_error    VARCHAR(500) NULL     COMMENT '最近一次失败原因',
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_cleanup (user_id, resource_type),
    KEY idx_account_cleanup_pending (status, attempts, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '账号注销遗留外部资源清理任务';

-- Idempotent column addition for databases that already ran an earlier revision of this script.
DROP PROCEDURE IF EXISTS ensure_phase10_cleanup_columns;
DELIMITER $$
CREATE PROCEDURE ensure_phase10_cleanup_columns()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'tb_account_cleanup_task' AND column_name = 'resource_ref') THEN
        ALTER TABLE tb_account_cleanup_task ADD COLUMN resource_ref VARCHAR(512) NULL
            COMMENT '待清理资源引用：token 串或头像 URL';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'tb_account_cleanup_task' AND column_name = 'attempts') THEN
        ALTER TABLE tb_account_cleanup_task ADD COLUMN attempts INT NOT NULL DEFAULT 0
            COMMENT '已尝试次数';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'tb_account_cleanup_task' AND column_name = 'last_error') THEN
        ALTER TABLE tb_account_cleanup_task ADD COLUMN last_error VARCHAR(500) NULL
            COMMENT '最近一次失败原因';
    END IF;
END$$
DELIMITER ;
CALL ensure_phase10_cleanup_columns();
DROP PROCEDURE IF EXISTS ensure_phase10_cleanup_columns;

-- Postflight: the table must exist and start empty on a fresh deployment.
SELECT table_name, table_rows FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name = 'tb_account_cleanup_task';
