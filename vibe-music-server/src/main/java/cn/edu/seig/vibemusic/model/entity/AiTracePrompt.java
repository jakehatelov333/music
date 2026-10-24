package cn.edu.seig.vibemusic.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 基础系统提示词版本库。
 *
 * <p><b>为什么要单独存原文：</b>只存哈希的话，规则一改历史 Trace 就再也无法还原
 * 「当时到底要求模型做什么」。按哈希去重后，同一版提示词只存一份，既能还原历史，
 * 又不会把同一段长文本重复写进每次请求。
 */
@Data
public class AiTracePrompt {

    private String promptHash;
    private String promptVersion;
    private String basePrompt;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
    private Long seenCount;
}
