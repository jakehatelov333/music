-- MySQL 8.0; repeatable. Run after backing up the database and before deploying phase 3.
-- Adds the playlist publication state and the cover provenance flag.
--
-- Compatibility guarantees:
--   * Every existing row becomes PUBLISHED, so current discovery behaviour is unchanged.
--   * cover_is_custom defaults to FALSE. Nothing already stored in cover_url is deleted or rewritten.
--   * Rows that never had a cover get the first bound song's cover, matching the detail query order
--     (ORDER BY pb.song_id). Songs themselves are only read, never modified.

-- Preflight: confirm the columns are absent before running the ALTERs below.
SELECT column_name, column_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'tb_playlist'
  AND column_name IN ('status', 'cover_is_custom');

DROP PROCEDURE IF EXISTS ensure_phase3_playlist_state;
DELIMITER $$
CREATE PROCEDURE ensure_phase3_playlist_state()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'tb_playlist' AND column_name = 'status') THEN
        -- Existing platform and personal playlists keep their current visibility behaviour.
        ALTER TABLE tb_playlist
            ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED'
            COMMENT 'DRAFT=草稿仅所有者/管理员可见; PUBLISHED=已发布进入可见性控制';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'tb_playlist' AND column_name = 'cover_is_custom') THEN
        -- FALSE: cover_url (when present) came from a song cover or from the first bound song.
        -- TRUE: the owner uploaded a cover manually; automatic defaults must never overwrite it.
        ALTER TABLE tb_playlist
            ADD COLUMN cover_is_custom BOOLEAN NOT NULL DEFAULT FALSE
            COMMENT 'TRUE=用户手动上传的封面，加歌时不再自动覆盖';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE()
        AND table_name = 'tb_playlist' AND index_name = 'idx_playlist_status_visibility') THEN
        ALTER TABLE tb_playlist ADD INDEX idx_playlist_status_visibility(status, is_public, id);
    END IF;
END$$
DELIMITER ;
CALL ensure_phase3_playlist_state();
DROP PROCEDURE IF EXISTS ensure_phase3_playlist_state;

-- Backfill: playlists without a cover adopt the cover of their first bound song.
-- Guarded by "cover_url IS NULL OR cover_url = ''" so it is safe to replay and never
-- overwrites an existing cover. Platform playlists that already have a cover are untouched.
UPDATE tb_playlist p
SET p.cover_url = (
    SELECT s.cover_url
    FROM tb_playlist_binding pb
             JOIN tb_song s ON s.id = pb.song_id
    WHERE pb.playlist_id = p.id
      AND s.cover_url IS NOT NULL
      AND s.cover_url <> ''
    ORDER BY pb.song_id
    LIMIT 1
)
WHERE (p.cover_url IS NULL OR p.cover_url = '')
  AND EXISTS (
      SELECT 1
      FROM tb_playlist_binding pb2
               JOIN tb_song s2 ON s2.id = pb2.song_id
      WHERE pb2.playlist_id = p.id
        AND s2.cover_url IS NOT NULL
        AND s2.cover_url <> ''
  );

-- Mark pre-existing platform covers as uploaded. Platform covers were curated by an administrator,
-- so replacing one must clean up the old object. A cover that is still referenced by a song is
-- excluded: it is a shared song image and must never be deleted by a playlist operation.
UPDATE tb_playlist p
SET p.cover_is_custom = TRUE
WHERE p.owner_user_id IS NULL
  AND p.cover_url IS NOT NULL
  AND p.cover_url <> ''
  AND NOT EXISTS (SELECT 1 FROM tb_song s WHERE s.cover_url = p.cover_url);

-- Postflight: status distribution, then every row that still lacks a cover.
SELECT status, COUNT(*) AS rows_in_status FROM tb_playlist GROUP BY status;
SELECT p.id, p.owner_user_id, p.is_public
FROM tb_playlist p
WHERE p.cover_url IS NULL OR p.cover_url = '';
SELECT p.id, p.title, p.cover_is_custom
FROM tb_playlist p
WHERE p.owner_user_id IS NULL AND p.cover_url IS NOT NULL AND p.cover_url <> '';
