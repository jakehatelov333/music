-- =============================================================================
-- 歌词补全功能迁移脚本
-- 新增：lyric_source（歌词来源）、lyric_status（歌词状态）、lyric_updated_at（更新时间）
-- 作用：支持"负缓存"（避免对确实没有歌词的纯音乐反复抓取）与覆盖率统计
-- 可重复执行：若列已存在会报 1060，可忽略
-- =============================================================================

ALTER TABLE `tb_song`
  ADD COLUMN `lyric_source` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL
      COMMENT '歌词来源: QQ_MUSIC / LRCLIB / MANUAL / INSTRUMENTAL' AFTER `lyric`,
  ADD COLUMN `lyric_status` tinyint NOT NULL DEFAULT 0
      COMMENT '歌词状态: 0未获取 1已获取 2手动录入 3无匹配 4伴奏或无需歌词' AFTER `lyric_source`,
  ADD COLUMN `lyric_updated_at` datetime NULL DEFAULT NULL
      COMMENT '歌词更新时间' AFTER `lyric_status`;

-- 存量数据回填：已有歌词的一律视为人工录入，避免被自动补全覆盖
UPDATE `tb_song`
   SET `lyric_status` = 2,
       `lyric_source` = 'MANUAL'
 WHERE `lyric` IS NOT NULL
   AND TRIM(`lyric`) <> '';
