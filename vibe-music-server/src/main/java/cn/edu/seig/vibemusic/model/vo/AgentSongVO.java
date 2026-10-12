package cn.edu.seig.vibemusic.model.vo;

import lombok.Data;

/**
 * Agent 工具专用的歌曲投影。
 *
 * <p>与 {@link SongVO} 的区别：这里带 {@code style}，且是 <b>Agent 独占</b>的只读投影。
 * 单独建 VO 而不是改 {@code SongVO}，是为了不动既有 REST 接口的响应契约。
 */
@Data
public class AgentSongVO {
    private Long songId;
    private String songName;
    private String artistName;
    private String album;
    private String style;
    private String duration;
    private String coverUrl;
    private String audioUrl;
}
