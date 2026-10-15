-- MySQL 8.0; repeatable (safe to run more than once). Back up the database before deploying.
--
-- Phase C (media cleanup reliability) adds one table:
--   * tb_media_cleanup_task : durable, per-object cleanup queue for MinIO objects whose
--                             business owner has just been deleted or replaced.
--
-- Why it exists: today the delete/replace paths call MinIO INSIDE (or before) the business
-- transaction. That produces two real defects:
--   1. A rolled-back business transaction still loses the file, because the S3 DELETE already
--      happened and S3 does not participate in the MySQL transaction.
--   2. A shared cover (e.g. one album cover referenced by 3 songs) is deleted when only ONE
--      of the referencing rows goes away, leaving the survivors pointing at a missing object.
-- Instead we record the object in this table in the SAME transaction as the business change,
-- and a scheduled worker deletes it later only after re-checking that no row references it.
--
-- No foreign key on purpose: the business row is already gone when the task is written.
-- object_key holds a MinIO object key (NOT a full URL), so the task never stores a hostname
-- or credentials. last_error is truncated and must never contain credentials.
--
-- Column notes that are load-bearing:
--   * object_key is VARCHAR(255) COLLATE utf8mb4_bin. The MinIO/S3 key namespace is
--     case-sensitive, but this schema's default collation (utf8mb4_0900_ai_ci) is not, so a
--     case-insensitive column would treat "A.jpg" and "a.jpg" as the same object. The column
--     collation is what keeps object identity honest.
--   * key_digest is SHA-256(object_key) as lowercase hex. The unique key uses the digest
--     instead of the raw key so the constraint is immune to collation and key-length limits.
--     Callers MUST still re-check the full object_key after a digest match, so a (theoretical)
--     digest collision cannot be mistaken for a hit.

CREATE TABLE IF NOT EXISTS tb_media_cleanup_task (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    bucket           VARCHAR(64)   NOT NULL COMMENT '对象所在桶，取自 minio.bucket 配置',
    object_key       VARCHAR(255)  COLLATE utf8mb4_bin NOT NULL COMMENT 'MinIO 对象键（大小写敏感，非完整 URL）',
    key_digest       CHAR(64)      NOT NULL COMMENT 'SHA-256(object_key) 小写十六进制，仅用于唯一约束',
    source_type      VARCHAR(32)   NOT NULL COMMENT '来源业务：SONG_DELETE / ALBUM_DELETE / SONG_COVER_REPLACE / SONG_AUDIO_REPLACE / ALBUM_COVER_REPLACE / PLAYLIST_COVER / UPLOAD_ABORT',
    source_ref       VARCHAR(64)   NOT NULL COMMENT '来源实体 id 或上传会话 id',
    reason           VARCHAR(64)   NULL     COMMENT '可读原因，便于人工排查',
    status           VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / RUNNING / SUCCEEDED / RETRY_WAIT / FAILED / RETAINED',
    attempts         INT           NOT NULL DEFAULT 0 COMMENT '已尝试次数',
    next_run_at      DATETIME(6)   NOT NULL COMMENT '下次可执行时间（退避用）',
    lease_owner      VARCHAR(64)   NULL     COMMENT '执行租约持有者，空表示无人在执行',
    lease_expires_at DATETIME(6)   NULL     COMMENT '租约到期时间，过期后可被重新领取',
    generation       BIGINT        NOT NULL DEFAULT 0 COMMENT '执行代次，旧执行者不得覆盖新代次结果',
    last_error       VARCHAR(500)  NULL     COMMENT '最近失败原因（脱敏，禁止写入凭据）',
    created_at       DATETIME(6)   NOT NULL,
    updated_at       DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_media_cleanup (source_type, source_ref, key_digest),
    KEY idx_media_cleanup_due (status, next_run_at, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '媒体对象清理任务（业务事务内登记，提交后由 worker 核对引用再删）';

-- Postflight: the table must exist and start empty on a fresh deployment.
SELECT table_name, table_rows FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name = 'tb_media_cleanup_task';

-- ---------------------------------------------------------------------------------------------
-- NOT CREATED YET — design only, reserved for Phase C2.
--
-- tb_media_object is the media registry that closes the remaining narrow race in C1: a worker
-- checks "reference count = 0" at T1 and deletes at T2, and a request in between may have just
-- pointed a NEW row at that object. Closing it requires every "add/replace reference" entry
-- point to lock the same registry row and re-check inside the lock, which touches every media
-- write path (song add/update, album add/update, playlist cover, artist avatar, user avatar,
-- banner, upload commit). That is deliberately out of scope for C1.
--
-- Kept here as a comment so the DDL review does not get lost. Do NOT uncomment in C1.
--
-- CREATE TABLE IF NOT EXISTS tb_media_object (
--     id                BIGINT       NOT NULL AUTO_INCREMENT,
--     bucket            VARCHAR(64)  NOT NULL,
--     object_key        VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
--     key_digest        CHAR(64)     NOT NULL COMMENT 'SHA-256(object_key) 小写十六进制',
--     status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DELETING / DELETED',
--     upload_session_id VARCHAR(64)  NULL COMMENT '来源上传会话',
--     created_at        DATETIME(6)  NOT NULL,
--     updated_at        DATETIME(6)  NOT NULL,
--     PRIMARY KEY (id),
--     UNIQUE KEY uk_media_object (bucket, key_digest)
-- ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '媒体对象登记表（C2 使用）';
