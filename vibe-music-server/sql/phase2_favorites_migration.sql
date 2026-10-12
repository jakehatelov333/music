-- MySQL 8.0: run after phase2_personal_playlists.sql, before serving new writes.
-- Never deletes favorites. If old duplicate/invalid rows exist, abort and inspect them first.
DROP PROCEDURE IF EXISTS ensure_phase2_favorites;
DELIMITER $$
CREATE PROCEDURE ensure_phase2_favorites()
BEGIN
    IF EXISTS (SELECT 1 FROM tb_user_favorite
               GROUP BY user_id, type, song_id, playlist_id HAVING COUNT(*) > 1) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Duplicate favorites found; inspect existing rows before migration';
    END IF;
    IF EXISTS (SELECT 1 FROM tb_user_favorite WHERE NOT (
        (type = 0 AND song_id IS NOT NULL AND playlist_id IS NULL) OR
        (type = 1 AND playlist_id IS NOT NULL AND song_id IS NULL))) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid favorite targets found; inspect existing rows before migration';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
        WHERE table_schema=DATABASE() AND table_name='tb_user_favorite' AND index_name='uk_favorite_song') THEN
        ALTER TABLE tb_user_favorite ADD UNIQUE KEY uk_favorite_song(user_id, type, song_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
        WHERE table_schema=DATABASE() AND table_name='tb_user_favorite' AND index_name='uk_favorite_playlist') THEN
        ALTER TABLE tb_user_favorite ADD UNIQUE KEY uk_favorite_playlist(user_id, type, playlist_id);
    END IF;
    -- MySQL 8 forbids CHECKs over columns used by our ON UPDATE CASCADE foreign keys.
    -- Existing foreign keys + separate unique indexes enforce existence and deduplication;
    -- service INSERTs fix type and set only the matching resource column.

END$$
DELIMITER ;
CALL ensure_phase2_favorites();
DROP PROCEDURE IF EXISTS ensure_phase2_favorites;
