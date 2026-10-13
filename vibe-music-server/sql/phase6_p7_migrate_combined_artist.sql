-- 歌曲多歌手合作演唱（phase 6）历史数据迁移脚本：组合歌手拆分 + 软删除
--
-- 对应 docs/phase6-song-multiple-artists.md §7.1 的五步，处理已人工确认的那一条数据：
--   组合记录「银临/Aki阿杰」→ 拆成「银临」（已有，id 由脚本解析）与「Aki阿杰」（新建），
--   再把《牵丝戏》的歌曲级关系、主艺人、展示串、专辑归属全部改到「银临」，
--   最后把组合记录标记为停用（**绝不物理删除**）。
--
-- 幂等：可重复执行。所有写操作都带「只在目标仍指向组合记录时才生效」的条件。
-- 前置：必须先执行过 phase6_song_multiple_artists.sql（需要 tb_song_artist 与 tb_artist.status）。
-- ⚠️ 执行前请备份数据库。
--
-- 执行：mysql ... --default-character-set=utf8mb4 vibe_music < phase6_p7_migrate_combined_artist.sql

-- 参数：本次人工确认的组合记录与拆分结果
SET @combo_name   := '银临/Aki阿杰';
SET @primary_name := '银临';
SET @feat_name    := 'Aki阿杰';

SET @combo_id   := (SELECT id FROM tb_artist WHERE name = @combo_name ORDER BY id LIMIT 1);
SET @primary_id := (SELECT id FROM tb_artist WHERE name = @primary_name AND status = 'ACTIVE' ORDER BY id LIMIT 1);
SET @feat_id    := (SELECT id FROM tb_artist WHERE name = @feat_name AND status = 'ACTIVE' ORDER BY id LIMIT 1);

-- ============================================================
-- Step 0：只读盘点（先看清楚要动什么）
-- ============================================================
SELECT 'Step0 解析出的 id' AS `check`, @combo_id AS `combo_id`, @primary_id AS `primary_id`, @feat_id AS `feat_id`;

SELECT 'Step0 引用组合记录的歌曲' AS `check`, COUNT(*) AS `songs`
FROM tb_song WHERE artist_id = @combo_id;

SELECT 'Step0 引用组合记录的专辑' AS `check`, COUNT(*) AS `albums`
FROM tb_album WHERE artist_id = @combo_id;

-- ============================================================
-- Step 1：确保独立歌手「Aki阿杰」存在（不自动创建其他任何歌手）
--         先查一次再插入，避免对目标表做自引用子查询。
-- ============================================================
INSERT INTO `tb_artist` (`name`, `status`)
SELECT @feat_name, 'ACTIVE' FROM DUAL
WHERE @feat_id IS NULL;

SET @feat_id := (SELECT id FROM tb_artist WHERE name = @feat_name AND status = 'ACTIVE' ORDER BY id LIMIT 1);

SELECT 'Step1 独立歌手 Aki阿杰 的 id' AS `check`, @feat_id AS `feat_id`;

-- ============================================================
-- Step 2：为仍挂在组合记录上的歌曲建立独立歌手关联
--         只有 @primary_id 与 @feat_id 都能解析出来才动手（否则整段为空操作）。
-- ============================================================
-- 先清掉指向组合记录的旧关系，避免组合歌手继续被歌曲引用
DELETE FROM `tb_song_artist` WHERE `artist_id` = @combo_id;

-- 主艺人：display_order = 1
INSERT IGNORE INTO `tb_song_artist` (`song_id`, `artist_id`, `display_order`)
SELECT s.`id`, @primary_id, 1
FROM `tb_song` s
WHERE s.`artist_id` = @combo_id
  AND @primary_id IS NOT NULL AND @feat_id IS NOT NULL;

-- 客串歌手：display_order = 2
INSERT IGNORE INTO `tb_song_artist` (`song_id`, `artist_id`, `display_order`)
SELECT s.`id`, @feat_id, 2
FROM `tb_song` s
WHERE s.`artist_id` = @combo_id
  AND @primary_id IS NOT NULL AND @feat_id IS NOT NULL;

-- ============================================================
-- Step 3：切断旧外键指向 —— 歌曲与专辑都要改
--         （只改 tb_song 而不改 tb_album，会让专辑主艺人继续指向停用记录）
-- ============================================================
UPDATE `tb_song` s
SET s.`artist_id`   = @primary_id,
    s.`artist_names` = (SELECT GROUP_CONCAT(a.`name` ORDER BY sa.`display_order`, sa.`artist_id` SEPARATOR ' / ')
                        FROM `tb_song_artist` sa
                        INNER JOIN `tb_artist` a ON a.`id` = sa.`artist_id`
                        WHERE sa.`song_id` = s.`id`)
WHERE s.`artist_id` = @combo_id
  AND @primary_id IS NOT NULL;

UPDATE `tb_album`
SET `artist_id` = @primary_id
WHERE `artist_id` = @combo_id
  AND @primary_id IS NOT NULL;

-- ============================================================
-- Step 4：校验（全部满足才进入 Step 5）
-- ============================================================
SELECT 'Step4.1 关系行数与顺序' AS `check`, `song_id`, `artist_id`, `display_order`
FROM `tb_song_artist` WHERE `song_id` IN (SELECT `id` FROM `tb_song` WHERE `artist_id` = @primary_id AND `name` = '牵丝戏')
ORDER BY `song_id`, `display_order`;

SELECT 'Step4.2 主艺人 = display_order 最小者（不一致行数，期望 0）' AS `check`, COUNT(*) AS `bad_rows`
FROM `tb_song` s
WHERE s.`artist_id` <> (SELECT sa.`artist_id` FROM `tb_song_artist` sa
                        WHERE sa.`song_id` = s.`id` ORDER BY sa.`display_order`, sa.`artist_id` LIMIT 1);

SELECT 'Step4.3 展示串与关系表拼串不一致（期望 0）' AS `check`, COUNT(*) AS `bad_rows`
FROM `tb_song` s
WHERE s.`artist_names` <> (SELECT GROUP_CONCAT(a.`name` ORDER BY sa.`display_order`, sa.`artist_id` SEPARATOR ' / ')
                           FROM `tb_song_artist` sa INNER JOIN `tb_artist` a ON a.`id` = sa.`artist_id`
                           WHERE sa.`song_id` = s.`id`);

SELECT 'Step4.4 仍指向组合记录的歌曲/专辑（期望都是 0）' AS `check`,
       (SELECT COUNT(*) FROM `tb_song`  WHERE `artist_id` = @combo_id) AS `songs`,
       (SELECT COUNT(*) FROM `tb_album` WHERE `artist_id` = @combo_id) AS `albums`;

SELECT 'Step4.5 组合记录是否仍被关系表引用（期望 0）' AS `check`, COUNT(*) AS `rows`
FROM `tb_song_artist` WHERE `artist_id` = @combo_id;

-- ============================================================
-- Step 5：标记停用（软删除）。绝不做物理删除 ——
--         tb_song.artist_id 的外键是 ON DELETE CASCADE，删除会连带删歌。
-- ============================================================
UPDATE `tb_artist`
SET `status` = 'DISABLED', `merged_into_artist_id` = @primary_id
WHERE `id` = @combo_id AND `status` = 'ACTIVE';

SELECT 'Step5 组合记录状态' AS `check`, `id`, `name`, `status`, `merged_into_artist_id`
FROM `tb_artist` WHERE `id` = @combo_id;
