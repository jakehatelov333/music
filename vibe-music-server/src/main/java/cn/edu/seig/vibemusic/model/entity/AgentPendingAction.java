package cn.edu.seig.vibemusic.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 服务端持久化的待确认动作。
 *
 * <p><b>为什么必须落库：</b>待确认数据如果只放在前端变量或模型上下文里，
 * 刷新页面就丢了，也无法在确认时重新鉴权。这里存的是“执行所需的全部信息 +
 * 展示给用户看过的预览快照”，因此页面刷新、换标签页、网络恢复后都能查回真实状态。
 *
 * <p><b>字段职责：</b>
 * <ul>
 *     <li>{@code taskId}：对外唯一的任务 ID（UUID），也是幂等键；</li>
 *     <li>{@code draftJson}：执行所需的业务参数（标题/简介/风格/可见性/歌曲 ID）；</li>
 *     <li>{@code previewJson}：用户确认时看到的预览快照，用于刷新后重建确认卡片；</li>
 *     <li>{@code version}：预览版本号，用户每次修改 +1，确认时可要求版本一致；</li>
 *     <li>{@code status}：状态机见 {@code PendingActionStatusEnum}；</li>
 *     <li>{@code resultPlaylistId}：成功后真实创建的歌单 ID，重复确认时原样返回。</li>
 * </ul>
 */
@Data
public class AgentPendingAction {

    private Long id;
    private String taskId;
    private Long userId;
    private String chatId;
    /** 目前只有 CREATE_PLAYLIST，为后续写操作预留。 */
    private String actionType;
    private String draftJson;
    private String previewJson;
    private Integer version;
    private String status;
    private Long resultPlaylistId;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime expiresAt;
}
