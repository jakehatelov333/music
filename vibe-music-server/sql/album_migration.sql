-- Album 领域增量迁移脚本（MySQL 8.0+）
-- 该脚本只新增专辑表和歌曲专辑字段，不会删除或重建现有歌曲数据。
-- 建议执行前先备份数据库，并以具有 ALTER 权限的账号运行。

CREATE TABLE IF NOT EXISTS `tb_album` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '专辑 id',
  `artist_id` bigint NOT NULL COMMENT '主艺人 id',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '专辑名称',
  `cover_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '专辑封面 url',
  `release_date` date NULL DEFAULT NULL COMMENT '发行日期',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '专辑简介',
  `status` varchar(20) NOT NULL DEFAULT 'DRAFT' COMMENT '专辑状态：DRAFT/PUBLISHED',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_album_artist_name` (`artist_id`, `name`) USING BTREE,
  KEY `idx_album_name` (`name`) USING BTREE,
  CONSTRAINT `fk_album_artist_id` FOREIGN KEY (`artist_id`) REFERENCES `tb_artist` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic COMMENT = '专辑';

DROP PROCEDURE IF EXISTS `ensure_album_schema`;

DELIMITER $$
CREATE PROCEDURE `ensure_album_schema`()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_song' AND COLUMN_NAME = 'album_id'
  ) THEN
    ALTER TABLE `tb_song` ADD COLUMN `album_id` bigint NULL DEFAULT NULL COMMENT '专辑 id，NULL 表示单曲' AFTER `artist_id`;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_song' AND COLUMN_NAME = 'disc_number'
  ) THEN
    ALTER TABLE `tb_song` ADD COLUMN `disc_number` int NOT NULL DEFAULT 1 COMMENT '唱片序号' AFTER `album`;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_song' AND COLUMN_NAME = 'track_number'
  ) THEN
    ALTER TABLE `tb_song` ADD COLUMN `track_number` int NOT NULL DEFAULT 1 COMMENT '曲序' AFTER `disc_number`;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_song' AND INDEX_NAME = 'idx_song_album_id'
  ) THEN
    ALTER TABLE `tb_song` ADD INDEX `idx_song_album_id` (`album_id`) USING BTREE;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_song'
      AND CONSTRAINT_NAME = 'fk_song_album_id' AND CONSTRAINT_TYPE = 'FOREIGN KEY'
  ) THEN
    ALTER TABLE `tb_song`
      ADD CONSTRAINT `fk_song_album_id` FOREIGN KEY (`album_id`) REFERENCES `tb_album` (`id`)
      ON DELETE SET NULL ON UPDATE CASCADE;
  END IF;
END$$
DELIMITER ;

CALL `ensure_album_schema`();
DROP PROCEDURE IF EXISTS `ensure_album_schema`;

-- 将当前以“艺人 + 专辑名”聚合出的历史歌曲初始化为正式专辑记录。
-- 若数据库里相同艺人有同名专辑冲突，唯一键会阻止合并；脚本不会按名称以外的条件猜测。
INSERT INTO `tb_album` (`artist_id`, `name`, `cover_url`, `release_date`)
SELECT
  s.`artist_id`,
  TRIM(s.`album`),
  MIN(s.`cover_url`),
  MIN(s.`release_time`)
FROM `tb_song` s
WHERE s.`album` IS NOT NULL AND TRIM(s.`album`) <> ''
GROUP BY s.`artist_id`, TRIM(s.`album`)
ON DUPLICATE KEY UPDATE
  `cover_url` = COALESCE(`cover_url`, VALUES(`cover_url`)),
  `release_date` = COALESCE(`release_date`, VALUES(`release_date`));

-- 只关联实际属于专辑的歌曲；专辑名为空的歌曲保持 album_id = NULL，继续按单曲处理。
UPDATE `tb_song` s
INNER JOIN `tb_album` a
  ON a.`artist_id` = s.`artist_id` AND a.`name` = TRIM(s.`album`)
SET s.`album_id` = a.`id`
WHERE s.`album` IS NOT NULL AND TRIM(s.`album`) <> '';

-- 历史数据默认单碟，并根据发行时间生成稳定曲序。
UPDATE `tb_song` s
INNER JOIN (
  SELECT
    `id`,
    ROW_NUMBER() OVER (PARTITION BY `album_id` ORDER BY `release_time`, `id`) AS `row_num`
  FROM `tb_song`
  WHERE `album_id` IS NOT NULL
) ranked ON ranked.`id` = s.`id`
SET s.`disc_number` = COALESCE(s.`disc_number`, 1),
    s.`track_number` = ranked.`row_num`;

-- Album 状态与审计时间（可重复执行）
SET @sql = (SELECT IF(
  EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tb_album' AND COLUMN_NAME='status'),
  'SELECT 1',
  'ALTER TABLE tb_album ADD COLUMN status varchar(20) NOT NULL DEFAULT ''DRAFT'' COMMENT ''专辑状态'' AFTER description'
)); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(
  EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tb_album' AND COLUMN_NAME='created_at'),
  'SELECT 1',
  'ALTER TABLE tb_album ADD COLUMN created_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间'''
)); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(
  EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tb_album' AND COLUMN_NAME='updated_at'),
  'SELECT 1',
  'ALTER TABLE tb_album ADD COLUMN updated_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''更新时间'''
)); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
UPDATE tb_album SET status = 'DRAFT' WHERE status IS NULL OR TRIM(status) = '';
UPDATE tb_album SET created_at = COALESCE(created_at, CURRENT_TIMESTAMP), updated_at = COALESCE(updated_at, CURRENT_TIMESTAMP);
