package cn.edu.seig.vibemusic.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AiChatRequest(@NotBlank(message = "消息不能为空")
                            @Size(max = 2000, message = "消息不能超过2000字") String query) {}
