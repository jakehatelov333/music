-- 第二批 / 聊天历史和模型窗口分离（MySQL 8）。先执行迁移，再发布后端。
-- 非破坏、可重复执行；保留 chat_messages 的全部旧记录及其原始 ID/时间。
-- context_json 首次为空时，应用仅加载最近最多 40 条旧记录生成上下文窗口。
-- 历史中此前已被旧版本裁剪删除的记录无法恢复。
-- 仓库早期基础导出不含聊天表；新安装创建该表，已有安装保持原表不变。
CREATE TABLE IF NOT EXISTS chat_messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    chat_id VARCHAR(255) NOT NULL,
    message_type VARCHAR(32) NOT NULL,
    content LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_chat_messages_chat_id_id (chat_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='按事件追加的完整聊天历史';

-- 给已有表补齐会话 + 稳定顺序索引；再次执行不会重复建索引。
SET @phase2_chat_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'chat_messages'
      AND index_name = 'idx_chat_messages_chat_id_id'
);
SET @phase2_chat_index_sql = IF(@phase2_chat_index_exists > 0,
    'SELECT 1', 'ALTER TABLE chat_messages ADD INDEX idx_chat_messages_chat_id_id (chat_id, id)');
PREPARE phase2_chat_index_stmt FROM @phase2_chat_index_sql;
EXECUTE phase2_chat_index_stmt;
DEALLOCATE PREPARE phase2_chat_index_stmt;

CREATE TABLE IF NOT EXISTS ai_chat_state (
    chat_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    context_json LONGTEXT NULL COMMENT '模型窗口，最多 20 条；不含系统提示词',
    context_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    active_run_token VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_expires_at DATETIME(6) NULL,
    PRIMARY KEY (chat_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型上下文与跨实例会话租约';
