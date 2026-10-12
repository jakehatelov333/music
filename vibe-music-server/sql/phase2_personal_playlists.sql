-- MySQL 8.0; safe to repeat; run after backing up the database and before deploying phase 2.
-- Existing rows remain PUBLIC platform playlists (owner_user_id NULL).
-- This migration neither deletes nor rewrites existing playlist bindings.
-- DDL auto-commits in MySQL. Review the preflight output before applying the ALTER.

-- Preflight: must return no rows. If it finds duplicates, STOP and resolve manually.
SELECT playlist_id, song_id, COUNT(*) AS duplicate_count
FROM tb_playlist_binding GROUP BY playlist_id, song_id HAVING COUNT(*) > 1;

-- Verify that the schema still has the original PRIMARY KEY (playlist_id, song_id),
-- plus cascading foreign keys to playlist and song. The supplied vibe_music.sql has both.
SHOW CREATE TABLE tb_playlist_binding;
SHOW CREATE TABLE tb_playlist;

DROP PROCEDURE IF EXISTS ensure_phase2_playlists;
DELIMITER $$
CREATE PROCEDURE ensure_phase2_playlists()
BEGIN
    IF EXISTS (SELECT 1 FROM tb_playlist_binding GROUP BY playlist_id,song_id HAVING COUNT(*) > 1) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Duplicate playlist bindings found; inspect before migration';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
        AND table_name='tb_playlist' AND column_name='owner_user_id') THEN
        ALTER TABLE tb_playlist ADD COLUMN owner_user_id BIGINT NULL COMMENT 'NULL=platform; otherwise owning user';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
        AND table_name='tb_playlist' AND column_name='is_public') THEN
        ALTER TABLE tb_playlist ADD COLUMN is_public BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'Public discovery visibility';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
        AND table_name='tb_playlist' AND index_name='idx_playlist_owner') THEN
        ALTER TABLE tb_playlist ADD INDEX idx_playlist_owner(owner_user_id,id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
        AND table_name='tb_playlist' AND index_name='idx_playlist_visibility') THEN
        ALTER TABLE tb_playlist ADD INDEX idx_playlist_visibility(is_public,id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
        AND table_name='tb_playlist' AND constraint_name='fk_playlist_owner') THEN
        ALTER TABLE tb_playlist ADD CONSTRAINT fk_playlist_owner FOREIGN KEY(owner_user_id) REFERENCES tb_user(id) ON DELETE CASCADE;
    END IF;
END$$
DELIMITER ;
CALL ensure_phase2_playlists();
DROP PROCEDURE IF EXISTS ensure_phase2_playlists;

-- The composite key serializes concurrent creation retries. playlist_id deliberately
-- has no FK: keep the receipt after deletion so a replay cannot recreate the playlist.
CREATE TABLE IF NOT EXISTS tb_playlist_create_request (
    user_id BIGINT NOT NULL,
    request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    playlist_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, request_key),
    CONSTRAINT fk_playlist_request_user FOREIGN KEY (user_id) REFERENCES tb_user(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- Optional postflight sanity check: every preexisting platform playlist stays public.
SELECT COUNT(*) AS invalid_platform_rows FROM tb_playlist WHERE owner_user_id IS NULL AND is_public = FALSE;
