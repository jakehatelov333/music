package cn.edu.seig.vibemusic.model.entity;

import lombok.Data;

/** Durable idempotency receipt, retained when the created playlist is deleted. */
@Data
public class PlaylistCreateRequest {
    private Long userId;
    private String requestKey;
    private String requestHash;
    private Long playlistId;
}
