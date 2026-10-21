package cn.edu.seig.vibemusic.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户播放历史。
 *
 * <p><b>每个 (user_id, song_id) 只有一行</b>，由 {@code uk_play_history_user_song} 保证。
 * 重复播放是 UPDATE（刷新 {@code lastPlayedAt}、累加 {@code playCount}），
 * 不是追加事件 —— 产品侧要的是「最近播放 + 播放了几次」，不是完整时间线；
 * 事件流会无限增长且需要独立的保留策略。
 *
 * <p><b>只统计有效播放</b>：{@code playCount} 的累加发生在
 * {@code PlayHistoryService#recordPlay}，只有客户端明确声明达到有效播放阈值时才调用。
 * 页面刚加载、误点击、初始化时的事件不会走到这里。
 *
 * <p><b>游客不写这张表</b>：访客的播放队列留在浏览器 localStorage 里，
 * 因此同一台机器上换人使用不会出现「历史混在一起」。
 *
 * <p>歌曲被删除时本行由 {@code ON DELETE CASCADE} 一并删除（见迁移脚本的说明）。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_play_history")
public class PlayHistory implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 历史记录 id
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long historyId;

    /**
     * 用户 id（只来自服务端认证上下文，绝不接受客户端传入）
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 歌曲 id
     */
    @TableField("song_id")
    private Long songId;

    /**
     * 最近一次有效播放时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField("last_played_at")
    private LocalDateTime lastPlayedAt;

    /**
     * 累计有效播放次数
     */
    @TableField("play_count")
    private Integer playCount;

    /**
     * 最近一次有效播放时的进度（秒）；客户端未上报时为 null。
     * 目前仅作留痕，前端不展示 —— 播放进度属于细粒度行为数据，没有展示需求就不外泄。
     */
    @TableField("last_position_seconds")
    private Integer lastPositionSeconds;

    /**
     * 最近一次有效播放的幂等键。
     *
     * <p>客户端为「一次有效播放」生成，重试或事件重复触发时复用同一个值，
     * 因此同一个键只会被计一次；换一次播放就是新的键，正常累加。
     * 与歌单创建用的 {@code Idempotency-Key} 是同一套思路。
     */
    @TableField("last_play_id")
    private String lastPlayId;

}
