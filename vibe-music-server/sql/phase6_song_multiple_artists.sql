-- 歌曲多歌手合作演唱（phase 6）迁移脚本（MySQL 8.0+）
--
-- 目的：为「一首歌曲由多位歌手合作演唱」建立权威关系表 tb_song_artist，
--       并在主表 tb_song 增加冗余展示串 artist_names。
--
-- 幂等：可重复执行。只新增表与列，不删除、不重建、不修改任何现有业务数据。
--       回填语句只填「空值」，已填过的行不会被覆盖。
-- 建议执行前先备份数据库，并以具有 CREATE / ALTER 权限的账号运行。
--
-- 语义约定（详见 docs/phase6-song-multiple-artists.md）：
--   * tb_song_artist            —— 唯一权威关系，判断「某歌手是否演唱某歌」只能查它；
--   * tb_song.artist_names      —— 派生展示串，禁止作为判断依据；
--   * tb_song.artist_id         —— 兼容字段，语义为「主艺人」（display_order 最小者）。
--
-- 执行：mysql ... --default-character-set=utf8mb4 vibe_music < phase6_song_multiple_artists.sql

-- ============================================================
-- 1. 歌曲-演唱歌手关系表（权威数据源）
-- ============================================================
CREATE TABLE IF NOT EXISTS `tb_song_artist` (
  `song_id`       bigint   NOT NULL COMMENT '歌曲 id',
  `artist_id`     bigint   NOT NULL COMMENT '歌手 id',
  `display_order` int      NOT NULL DEFAULT 1 COMMENT '展示顺序，从 1 开始连续',
  `created_at`    datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`song_id`, `artist_id`) USING BTREE,
  KEY `idx_song_artist_artist` (`artist_id`, `song_id`) USING BTREE,
  KEY `idx_song_artist_song_order` (`song_id`, `display_order`) USING BTREE,
  CONSTRAINT `fk_song_artist_song`   FOREIGN KEY (`song_id`)   REFERENCES `tb_song` (`id`)   ON DELETE CASCADE  ON UPDATE CASCADE,
  CONSTRAINT `fk_song_artist_artist` FOREIGN KEY (`artist_id`) REFERENCES `tb_artist` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic
  COMMENT = '歌曲-演唱歌手关系（权威数据源）';

-- ============================================================
-- 2. tb_song 增加冗余展示串
-- ============================================================
DROP PROCEDURE IF EXISTS `ensure_song_artist_schema`;

DELIMITER $$
CREATE PROCEDURE `ensure_song_artist_schema`()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_song' AND COLUMN_NAME = 'artist_names'
  ) THEN
    ALTER TABLE `tb_song` ADD COLUMN `artist_names` varchar(512) NULL DEFAULT NULL
      COMMENT '歌手展示串，按 display_order 用 " / " 拼接；派生自 tb_song_artist' AFTER `artist_id`;
  END IF;
END$$
DELIMITER ;

CALL `ensure_song_artist_schema`();
DROP PROCEDURE IF EXISTS `ensure_song_artist_schema`;

-- ============================================================
-- 3. 回填：先把「单歌手」这一既有事实搬进关系表
--    本步骤不修改 tb_song.artist_id，因此任何时刻歌曲都不会失去歌手关联。
-- ============================================================

-- 3.1 每首歌曲写入一条 display_order = 1 的关系。
--     INSERT IGNORE 依赖主键 (song_id, artist_id) 去重，因此可重复执行；
--     已有多歌手关系的歌曲不会被本语句改写。
INSERT IGNORE INTO `tb_song_artist` (`song_id`, `artist_id`, `display_order`)
SELECT s.`id`, s.`artist_id`, 1
FROM `tb_song` s
INNER JOIN `tb_artist` a ON a.`id` = s.`artist_id`
WHERE s.`artist_id` IS NOT NULL;

-- 3.2 展示串回填：只填空值，已填过（含后续人工迁移的多歌手串）的行不受影响。
UPDATE `tb_song` s
INNER JOIN `tb_artist` a ON a.`id` = s.`artist_id`
SET s.`artist_names` = a.`name`
WHERE s.`artist_names` IS NULL OR TRIM(s.`artist_names`) = '';

-- ============================================================
-- 4. 歌手软删除（P5）
--    「银临/Aki阿杰」这类组合记录迁移完引用后必须停用而不是删除：
--    tb_song.artist_id 的外键是 ON DELETE CASCADE，物理删除会连带删歌。
-- ============================================================
DROP PROCEDURE IF EXISTS `ensure_artist_status_schema`;

DELIMITER $$
CREATE PROCEDURE `ensure_artist_status_schema`()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_artist' AND COLUMN_NAME = 'status'
  ) THEN
    ALTER TABLE `tb_artist` ADD COLUMN `status` varchar(20) NOT NULL DEFAULT 'ACTIVE'
      COMMENT '状态：ACTIVE / DISABLED（软删除）' AFTER `introduction`;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_artist' AND COLUMN_NAME = 'merged_into_artist_id'
  ) THEN
    ALTER TABLE `tb_artist` ADD COLUMN `merged_into_artist_id` bigint NULL DEFAULT NULL
      COMMENT '若因合并而停用，记录归并到的歌手 id' AFTER `status`;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_artist' AND INDEX_NAME = 'idx_artist_status'
  ) THEN
    ALTER TABLE `tb_artist` ADD INDEX `idx_artist_status` (`status`) USING BTREE;
  END IF;
END$$
DELIMITER ;

CALL `ensure_artist_status_schema`();
DROP PROCEDURE IF EXISTS `ensure_artist_status_schema`;

-- 历史行补齐状态值（幂等）
UPDATE `tb_artist` SET `status` = 'ACTIVE' WHERE `status` IS NULL OR TRIM(`status`) = '';

-- 校验：不应存在非法状态
SELECT '4.1 非法歌手状态' AS `check`, COUNT(*) AS `bad_rows`
FROM `tb_artist` WHERE `status` NOT IN ('ACTIVE', 'DISABLED');

SELECT '4.2 歌手状态分布' AS `check`, `status`, COUNT(*) AS `artists`
FROM `tb_artist` GROUP BY `status`;

-- ============================================================
-- 5. 校验（只读，可反复执行）
--    期望：关系表行数 = 歌曲表行数；展示串无空值；无重复 song_id；无 order=1 缺失。
-- ============================================================

-- 5.1 关系表行数 vs 歌曲表行数
SELECT '5.1 关系表行数' AS `check`,
       (SELECT COUNT(*) FROM `tb_song_artist`) AS `song_artist_rows`,
       (SELECT COUNT(*) FROM `tb_song`)        AS `song_rows`;

-- 5.2 展示串为空的歌曲数（期望 0）
SELECT '5.2 展示串为空' AS `check`, COUNT(*) AS `bad_rows`
FROM `tb_song` WHERE `artist_names` IS NULL OR TRIM(`artist_names`) = '';

-- 5.3 每首歌的关系行数分布（迁移后应全部为 1）
SELECT '5.3 关系行数分布' AS `check`, `cnt` AS `relations_per_song`, COUNT(*) AS `songs`
FROM (SELECT `song_id`, COUNT(*) AS `cnt` FROM `tb_song_artist` GROUP BY `song_id`) t
GROUP BY `cnt` ORDER BY `cnt`;

-- 5.4 有歌曲但没有关系行（期望 0）
SELECT '5.4 缺少关系行的歌曲' AS `check`, COUNT(*) AS `bad_rows`
FROM `tb_song` s
WHERE NOT EXISTS (SELECT 1 FROM `tb_song_artist` sa WHERE sa.`song_id` = s.`id`);

-- 5.5 主艺人与 display_order=1 不一致（期望 0）
SELECT '5.5 主艺人不一致' AS `check`, COUNT(*) AS `bad_rows`
FROM `tb_song` s
WHERE s.`artist_id` <> (
  SELECT sa.`artist_id` FROM `tb_song_artist` sa
  WHERE sa.`song_id` = s.`id` ORDER BY sa.`display_order`, sa.`artist_id` LIMIT 1
);

-- 5.6 展示串与关系表拼串不一致（期望 0）
SELECT '5.6 展示串不一致' AS `check`, COUNT(*) AS `bad_rows`
FROM `tb_song` s
WHERE s.`artist_names` <> (
  SELECT GROUP_CONCAT(a.`name` ORDER BY sa.`display_order`, sa.`artist_id` SEPARATOR ' / ')
  FROM `tb_song_artist` sa
  INNER JOIN `tb_artist` a ON a.`id` = sa.`artist_id`
  WHERE sa.`song_id` = s.`id`
);
