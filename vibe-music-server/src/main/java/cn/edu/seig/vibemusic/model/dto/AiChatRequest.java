package cn.edu.seig.vibemusic.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 一次对话请求。
 *
 * <p><b>为什么 {@code chatId} 是可选而不是必填：</b>升级前的客户端只发 {@code query}，
 * 服务端按身份推导出唯一的会话。保留"不传即沿用默认会话"的语义，
 * 老客户端不需要同步发版就能继续工作。
 *
 * <p><b>{@code chatId} 只是"我想要哪段对话"，不是"我拥有哪段对话"。</b>
 * 归属一律由服务端按登录身份校验（见 {@code ConversationService#requireOwned}），
 * 传入不属于自己的 ID 会得到 404。
 */
public record AiChatRequest(@NotBlank(message = "消息不能为空")
                            @Size(max = 2000, message = "消息不能超过2000字") String query,
                            @Size(max = 255, message = "会话标识过长") String chatId,
                            Boolean newConversation) {
}
