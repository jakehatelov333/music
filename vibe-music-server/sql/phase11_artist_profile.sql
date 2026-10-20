-- MySQL 8.0; repeatable (safe to run more than once). Back up the database before deploying.
--
-- Phase 11 (artist profile autofill) — widen tb_artist.introduction.
--
-- 背景：新增「从 QQ 音乐搜索并自动填充歌手资料」后，简介直接来自对方的
-- `ex_info.desc`。对库里现有 129 位歌手做了实测：
--
--   简介长度分布（n=126，能搜到并取到详情的）
--     0 字        :  5
--     1–255 字    : 62
--     256–500 字  : 35
--     501–1000 字 : 18
--     > 1000 字   :  6     最长 2765 字，平均 357 字
--
-- 也就是 59 / 126（46.8%）超过当前 `varchar(255)`。而本项目连接的 MySQL 开着
-- `STRICT_TRANS_TABLES`（实测 `@@sql_mode` 含该项），超长不是截断而是直接报错：
--
--   ERROR 1406 (22001): Data too long for column 'v' at row 1
--
-- 即一半歌手点「自动填充」都会失败。因此把列放宽到 TEXT。
--
-- 为什么是 TEXT 而不是 varchar(3000)：
--   * TEXT 上限 65535 字节，对简介这种自由文本足够，且不必再猜一个上限；
--   * 现有数据最长 217 字，本次变更对历史行没有任何影响（只改类型，不改内容）。
--
-- 为什么需要 MODIFY 而不是等应用层截断：应用层截断会静默丢掉用户看得见的内容，
-- 而「自动填充后简介莫名其妙少了一半」是比报错更难排查的问题。列本身能容纳，
-- 就不该在应用层做有损转换。

SET @ddl := (
    SELECT IF(
        EXISTS(
            SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'tb_artist'
              AND COLUMN_NAME = 'introduction'
              AND DATA_TYPE = 'text'
        ),
        'SELECT ''tb_artist.introduction 已是 TEXT，跳过'' AS note',
        'ALTER TABLE tb_artist MODIFY COLUMN introduction TEXT NULL COMMENT ''歌手简介'''
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 回滚（谨慎：若已写入超过 255 字的内容，回滚会失败或截断，需先人工清理）
--   ALTER TABLE tb_artist MODIFY COLUMN introduction VARCHAR(255) NULL COMMENT '歌手简介';
