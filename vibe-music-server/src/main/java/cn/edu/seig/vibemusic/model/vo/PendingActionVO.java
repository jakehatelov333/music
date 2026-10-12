package cn.edu.seig.vibemusic.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 待确认任务对外的视图。前端据此渲染确认卡片，并在刷新后重建。
 *
 * <p>{@code songs} 是确认时用户看到的歌曲快照；{@code playlistId} 是成功后真实创建的歌单 ID。
 */
@Data
public class PendingActionVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String taskId;
    private String actionType;
    /** PENDING / EXECUTING / SUCCEEDED / FAILED / CANCELLED / EXPIRED */
    private String status;
    private String statusDescription;
    private String title;
    private String introduction;
    private String style;
    private Boolean isPublic;
    private Integer version;
    private List<cn.edu.seig.vibemusic.model.agent.SongRef> songs;
    private Integer songCount;
    /** 成功后真实创建的歌单 ID，未成功时为 null。 */
    private Long playlistId;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
}
