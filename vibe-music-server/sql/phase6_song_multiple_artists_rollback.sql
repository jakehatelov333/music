-- 歌曲多歌手合作演唱（phase 6）回滚脚本（MySQL 8.0+）
--
-- ⚠️ 破坏性操作：执行后 tb_song_artist 的全部关系数据与 tb_song.artist_names 会丢失。
--    仅在确认要放弃 phase 6 改造、且已备份数据库时执行。
--    若已执行过 P7（历史数据迁移：拆分《牵丝戏》、停用歌手 144），回滚前请先确认
--    tb_song / tb_album 的 artist_id 是否仍需要还原，本脚本不负责还原它们。
--
-- 执行：mysql ... --default-character-set=utf8mb4 vibe_music < phase6_song_multiple_artists_rollback.sql

-- 1. 删除关系表（外键随之删除，不影响 tb_song / tb_artist 数据）
DROP TABLE IF EXISTS `tb_song_artist`;

-- 2. 移除冗余展示串
--    若只想停用多歌手能力、但希望保留展示串数据，请注释掉本行。
ALTER TABLE `tb_song` DROP COLUMN `artist_names`;

-- 3. 校验：确认已回滚
SELECT 'rollback check: tb_song_artist 应不存在' AS `check`,
       COUNT(*) AS `table_exists`
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_song_artist';

SELECT 'rollback check: artist_names 列应不存在' AS `check`,
       COUNT(*) AS `column_exists`
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_song' AND COLUMN_NAME = 'artist_names';
