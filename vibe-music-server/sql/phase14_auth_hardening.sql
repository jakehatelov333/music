-- Phase 14: authentication/session hardening.
-- Run once against an existing database before deploying this code revision.

ALTER TABLE tb_user
    ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0,
    MODIFY COLUMN password VARCHAR(100) NOT NULL;

ALTER TABLE tb_admin
    ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0,
    MODIFY COLUMN password VARCHAR(100) NOT NULL;

-- Invalidate every credential copied from the historical shared administrator seed.
-- Administrators must be provisioned with a unique BCrypt hash through the controlled
-- initialization path after this migration.
UPDATE tb_admin
SET password = CONCAT('!disabled-', id, '-', REPLACE(UUID(), '-', '')),
    auth_version = auth_version + 1
WHERE password = 'df10ef8509dc176d733d59549e7dbfaf';

-- Historical account-cleanup rows may still contain raw bearer tokens. The new
-- session store cannot derive the Redis key from those legacy values, so retire
-- the rows without retaining or logging the secret.
UPDATE tb_account_cleanup_task
SET resource_ref = NULL,
    status = 'DONE',
    last_error = 'RETIRED_PLAINTEXT_TOKEN',
    updated_at = CURRENT_TIMESTAMP(6)
WHERE resource_type = 'REDIS_TOKEN'
  AND resource_ref IS NOT NULL;
