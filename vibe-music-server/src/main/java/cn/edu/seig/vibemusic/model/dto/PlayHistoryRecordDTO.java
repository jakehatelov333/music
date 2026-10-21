package cn.edu.seig.vibemusic.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 一次有效播放上报。
 *
 * <p><b>「有效」由客户端判定，服务端只做边界校验</b>：只有播放器确实推进到
 * 有效播放阈值（见前端 {@code utils/playHistory.ts} 的 {@code EFFECTIVE_PLAY_* }）后，
 * 才会调用这个接口。服务端无法感知音频是否真的出声，因此这里不重复实现判定逻辑，
 * 但会拒绝明显不合理的输入（非法 songId、负进度、超长幂等键）。
 *
 * <p><b>刻意没有 userId</b>：归属只来自服务端认证上下文。客户端传什么用户 id 都不会被读取，
 * 因此不存在「伪造别人的播放历史」这条路径。
 *
 * <p><b>{@code playId} 是幂等键</b>：客户端为每一次播放尝试生成一个，
 * 网络重试时复用同一个值。重复提交不会重复计数（见
 * {@code PlayHistoryMapper#recordPlay}）。允许为 null —— 老客户端不传就不去重，
 * 而不是拒绝请求（宁可少一层幂等，也不要因为缺字段丢历史）。
 */
@Data
public class PlayHistoryRecordDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 歌曲 id
     */
    @NotNull(message = "歌曲 id 不能为空")
    @Min(value = 1, message = "歌曲 id 不合法")
    private Long songId;

    /**
     * 本次播放已推进的秒数（用于留痕；不参与有效播放判定）
     */
    @Min(value = 0, message = "播放进度不能为负数")
    @Max(value = 86400, message = "播放进度超出合理范围")
    private Integer positionSeconds;

    /**
     * 幂等键：同一次播放的重试必须复用同一个值。
     */
    @Size(max = 64, message = "幂等键长度不能超过 64")
    @Pattern(regexp = "^[A-Za-z0-9_-]*$", message = "幂等键只能包含字母、数字、横线和下划线")
    private String playId;

}
