-- MySQL 8.0; repeatable (safe to run more than once). Back up the database before deploying.
--
-- Phase 4 (Agent engineering, items 9-12) adds two tables:
--   * tb_agent_pending_action : server-side, durable "waiting for user confirmation" tasks.
--                               Without it, the confirmation flow could only live in the browser
--                               or in the model context, so a refresh would lose it and the
--                               server could not re-authorize at confirm time.
--   * tb_ai_task_metric       : one row per Agent task, holding real execution metrics
--                               (model calls, provider-reported token usage, tool calls,
--                               latency, business outcome, failure category).
--
-- Neither table stores user messages, model replies, API keys or credentials.

-- ---------------------------------------------------------------- pending actions
CREATE TABLE IF NOT EXISTS tb_agent_pending_action (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    task_id            VARCHAR(64)  NOT NULL COMMENT '对外任务 ID，同时是幂等键（UUID）',
    user_id            BIGINT       NOT NULL COMMENT '任务所属用户，来自服务端认证',
    chat_id            VARCHAR(64)  NOT NULL COMMENT '关联会话，用于页面刷新后重建确认卡片',
    action_type        VARCHAR(32)  NOT NULL COMMENT 'CREATE_PLAYLIST 等',
    draft_json         TEXT         NOT NULL COMMENT '执行所需的业务参数',
    preview_json       TEXT         NOT NULL COMMENT '用户确认时看到的预览快照',
    version            INT          NOT NULL DEFAULT 1 COMMENT '预览版本号，用户每次修改 +1',
    status             VARCHAR(16)  NOT NULL COMMENT 'PENDING/EXECUTING/SUCCEEDED/FAILED/CANCELLED/EXPIRED',
    result_playlist_id BIGINT       NULL COMMENT '成功后真实创建的歌单 ID',
    error_code         VARCHAR(64)  NULL,
    error_message      VARCHAR(500) NULL,
    created_at         DATETIME     NOT NULL,
    updated_at         DATETIME     NOT NULL,
    expires_at         DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_action_task (task_id),
    KEY idx_agent_action_pending (user_id, chat_id, status),
    KEY idx_agent_action_sweep (status, expires_at),
    CONSTRAINT fk_agent_action_user FOREIGN KEY (user_id) REFERENCES tb_user (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Agent 待确认动作（确认前不写业务数据）';

-- ---------------------------------------------------------------- task metrics
CREATE TABLE IF NOT EXISTS tb_ai_task_metric (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    task_id          VARCHAR(64)  NOT NULL COMMENT '与 SSE start 事件中的 taskId 一致',
    user_id          BIGINT       NULL COMMENT '游客任务为 NULL',
    chat_id          VARCHAR(64)  NOT NULL,
    model            VARCHAR(64)  NOT NULL COMMENT '实际使用的模型名',
    model_calls      INT          NOT NULL DEFAULT 0,
    tool_calls       INT          NOT NULL DEFAULT 0,
    tool_failures    INT          NOT NULL DEFAULT 0,
    input_tokens     BIGINT       NULL COMMENT '仅当 token_source=PROVIDER 时有效',
    output_tokens    BIGINT       NULL COMMENT '仅当 token_source=PROVIDER 时有效',
    token_source     VARCHAR(16)  NOT NULL DEFAULT 'UNAVAILABLE' COMMENT 'PROVIDER=供应商实测 / UNAVAILABLE=未获取',
    result_count     INT          NOT NULL DEFAULT 0 COMMENT '检索到的业务结果条数',
    success          BOOLEAN      NOT NULL DEFAULT FALSE,
    failure_category VARCHAR(32)  NULL COMMENT 'NONE/MODEL_ERROR/TOOL_ERROR/TIMEOUT/INTERRUPTED/BUSINESS_ERROR/UNKNOWN',
    failure_message  VARCHAR(500) NULL,
    output_guarded   BOOLEAN      NOT NULL DEFAULT FALSE COMMENT 'TRUE=模型声称执行了写操作但服务端无对应事实，话术已被护栏替换',
    latency_ms       BIGINT       NOT NULL DEFAULT 0,
    playlist_id      BIGINT       NULL COMMENT '本任务真实创建的歌单 ID',
    created_at       DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_task_metric_task (task_id),
    KEY idx_ai_task_metric_user (user_id, id),
    KEY idx_ai_task_metric_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Agent 任务执行指标（不含消息内容与凭据）';

-- Idempotent column addition for databases that already ran an earlier revision of this script.
DROP PROCEDURE IF EXISTS ensure_phase4_output_guarded;
DELIMITER $$
CREATE PROCEDURE ensure_phase4_output_guarded()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'tb_ai_task_metric' AND column_name = 'output_guarded') THEN
        ALTER TABLE tb_ai_task_metric
            ADD COLUMN output_guarded BOOLEAN NOT NULL DEFAULT FALSE
            COMMENT 'TRUE=模型声称执行了写操作但服务端无对应事实，话术已被护栏替换';
    END IF;
END$$
DELIMITER ;
CALL ensure_phase4_output_guarded();
DROP PROCEDURE IF EXISTS ensure_phase4_output_guarded;

-- Postflight: both tables must exist and start empty on a fresh deployment.
SELECT table_name, table_rows
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('tb_agent_pending_action', 'tb_ai_task_metric');
