-- MySQL 8.0; repeatable (safe to run more than once). Back up the database before deploying.
--
-- Phase 13 — AI observability (LangSmith-style trace / span storage).
--
-- 背景：tb_ai_task_metric 只保存「一次请求的汇总」，无法回答下面这些问题：
--   * 第 2 次模型调用实际收到了哪些消息？系统提示词当时是哪一版？
--   * 模型提出调用 searchSongs，后端真正执行了吗？参数是什么？返回了什么？
--   * 这次回答变差，是模型选错工具、工具参数错、工具执行失败，还是最终话术被护栏替换？
-- 本迁移新增 4 张表，全部<b>只增不改</b>，不清空任何现有业务数据：
--   tb_ai_trace_span        : Trace / Span 元数据（执行树，含父 Span）
--   tb_ai_trace_llm_call    : 单次模型调用的输入/输出/工具定义快照
--   tb_ai_trace_tool_call   : 单次工具调用的模型请求 ↔ 后端执行明细
--   tb_ai_trace_prompt      : 基础系统提示词版本库（按哈希去重，便于还原历史规则）
--
-- 隐私：不记录 Authorization / Cookie / 密码 / API Key。消息与参数在写入前经
-- TraceRedactor 脱敏与截断，并在 snapshot_state 中标注 FULL / TRUNCATED / REDACTED / DISABLED，
-- 任何截断或关闭采集都不会被伪装成完整原文。

-- ---------------------------------------------------------------- trace / span
CREATE TABLE IF NOT EXISTS tb_ai_trace_span (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    trace_id        VARCHAR(64)  NOT NULL COMMENT '一次 AI 请求；与 tb_ai_task_metric.task_id 一致',
    span_id         VARCHAR(96)  NOT NULL COMMENT '本步骤 ID；TOOL 类型与 SSE toolCallId 相同',
    parent_span_id  VARCHAR(96)  NULL COMMENT '父步骤；根 Span 为 NULL',
    chat_id         VARCHAR(64)  NULL COMMENT '会话 Thread',
    user_id         BIGINT       NULL COMMENT '游客为 NULL（匿名 Trace 仍可查询）',
    span_type       VARCHAR(24)  NOT NULL COMMENT 'REQUEST/CONTEXT/MODEL/TOOL/GUARD/FINAL',
    name            VARCHAR(128) NOT NULL,
    status          VARCHAR(16)  NOT NULL COMMENT 'OK/ERROR/REJECTED/EMPTY/RUNNING',
    started_at      DATETIME(3)  NOT NULL,
    ended_at        DATETIME(3)  NULL,
    duration_ms     BIGINT       NULL,
    model           VARCHAR(64)  NULL,
    input_tokens    BIGINT       NULL,
    output_tokens   BIGINT       NULL,
    token_source    VARCHAR(16)  NULL COMMENT 'PROVIDER / UNAVAILABLE',
    error_category  VARCHAR(32)  NULL,
    error_message   VARCHAR(500) NULL,
    request_summary VARCHAR(300) NULL COMMENT '仅根 Span：脱敏后的请求摘要，列表页直接取用',
    attributes_json MEDIUMTEXT   NULL COMMENT '脱敏后的结构化属性（提示词版本、块状态、护栏结论等）',
    created_at      DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_trace_span (span_id),
    KEY idx_ai_trace_span_trace (trace_id, started_at),
    KEY idx_ai_trace_span_user (user_id, started_at),
    KEY idx_ai_trace_span_chat (chat_id, started_at),
    KEY idx_ai_trace_span_type (span_type, started_at),
    KEY idx_ai_trace_span_started (started_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AI Trace 执行树（不含凭据）';

-- ---------------------------------------------------------------- llm call
CREATE TABLE IF NOT EXISTS tb_ai_trace_llm_call (
    id                   BIGINT      NOT NULL AUTO_INCREMENT,
    span_id              VARCHAR(96) NOT NULL COMMENT '对应 tb_ai_trace_span.span_id',
    trace_id             VARCHAR(64) NOT NULL,
    call_index           INT         NOT NULL COMMENT '本次请求内第几次模型调用，从 1 开始',
    messages_json        MEDIUMTEXT  NULL COMMENT '当次真实输入消息（含 system），已脱敏/截断',
    system_message       MEDIUMTEXT  NULL COMMENT '当次实际生效的 System Message',
    prompt_hash          CHAR(64)    NULL COMMENT '基础提示词哈希，关联 tb_ai_trace_prompt',
    tools_json           MEDIUMTEXT  NULL COMMENT '当次模型可见的工具定义（含参数 Schema）',
    tools_hash           CHAR(64)    NULL,
    output_text          MEDIUMTEXT  NULL COMMENT '模型本轮输出文本',
    tool_requests_json   TEXT        NULL COMMENT '模型提出的 Tool Call 请求（名称 + 原始参数 + 模型侧 ID）',
    finish_reason        VARCHAR(32) NULL,
    snapshot_state       VARCHAR(24) NOT NULL DEFAULT 'FULL' COMMENT 'FULL/TRUNCATED/REDACTED/DISABLED',
    snapshot_note        VARCHAR(255) NULL COMMENT '截断或关闭采集的说明，展示时不得省略',
    created_at           DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_trace_llm_span (span_id),
    KEY idx_ai_trace_llm_trace (trace_id, call_index)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '单次模型调用明细';

-- ---------------------------------------------------------------- tool call
CREATE TABLE IF NOT EXISTS tb_ai_trace_tool_call (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    span_id             VARCHAR(96)  NOT NULL COMMENT '工具步骤 ID，同时是 SSE toolCallId',
    trace_id            VARCHAR(64)  NOT NULL,
    tool_name           VARCHAR(64)  NOT NULL,
    model_tool_call_id  VARCHAR(128) NULL COMMENT '模型响应里的 Tool Call ID（供应商给出）',
    server_tool_call_id VARCHAR(96)  NULL COMMENT '服务端为本次执行分配的 ID',
    raw_arguments       TEXT         NULL COMMENT '模型生成的原始参数 JSON',
    parsed_arguments    TEXT         NULL COMMENT '服务端解析后的参数',
    status              VARCHAR(16)  NOT NULL COMMENT 'OK/EMPTY/REJECTED/INVALID_ARGUMENT/UNAUTHORIZED/ERROR',
    result_code         VARCHAR(32)  NULL,
    result_total        INT          NULL,
    result_summary      VARCHAR(500) NULL,
    result_payload      MEDIUMTEXT   NULL COMMENT '脱敏后的工具返回数据',
    error_category      VARCHAR(32)  NULL,
    duration_ms         BIGINT       NULL,
    snapshot_state      VARCHAR(24)  NOT NULL DEFAULT 'FULL',
    snapshot_note       VARCHAR(255) NULL,
    created_at          DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_trace_tool_span (span_id),
    KEY idx_ai_trace_tool_trace (trace_id),
    KEY idx_ai_trace_tool_name (tool_name, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '单次工具调用明细（模型请求 ↔ 后端执行）';

-- ---------------------------------------------------------------- prompt registry
CREATE TABLE IF NOT EXISTS tb_ai_trace_prompt (
    prompt_hash  CHAR(64)    NOT NULL COMMENT '基础系统提示词 SHA-256',
    prompt_version VARCHAR(32) NOT NULL COMMENT '短版本号（哈希前 12 位），用于界面展示',
    base_prompt  MEDIUMTEXT  NOT NULL COMMENT '基础系统提示词原文，保证历史版本可查看',
    first_seen_at DATETIME   NOT NULL,
    last_seen_at  DATETIME   NOT NULL,
    seen_count   BIGINT      NOT NULL DEFAULT 1,
    PRIMARY KEY (prompt_hash),
    KEY idx_ai_trace_prompt_version (prompt_version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '基础系统提示词版本库（按哈希去重）';
