package cn.edu.seig.vibemusic.controller;

import cn.edu.seig.vibemusic.model.vo.ConversationVO;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.security.PlaylistAccessService;
import cn.edu.seig.vibemusic.service.agent.ConversationService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 会话列表与删除。
 *
 * <p>路径挂在 {@code /ai/} 下以复用既有的角色-路径权限（ROLE_USER 拥有 {@code /ai/} 前缀）。
 * 虽然 {@code /ai/**} 对匿名开放，但本控制器的每个方法都会调用
 * {@code PlaylistAccessService#requireUserId()}，游客一律 401 ——
 * 「能到达控制器」不等于「能看会话列表」，权限判定始终在服务端。
 *
 * <p><b>为什么没有「新建会话」接口：</b>新建是在发送第一条消息时随 {@code /ai/chat/stream}
 * 一起完成的（{@code newConversation: true}），会话标识由 {@code start} 事件回传给客户端。
 * 单独提供一个 POST 会多一次往返，并且必然产生「建了但没说话」的空会话行 ——
 * 让创建与第一次发言成为同一个动作，才能从源头上不产生空会话。
 */
@RestController
@RequestMapping("/ai/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversations;

    @ModelAttribute
    public void preventCaching(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    /** 当前登录用户的最近会话，按最近活跃时间倒序。空会话不会出现。 */
    @GetMapping
    public Result<List<ConversationVO>> list(
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        return Result.success(conversations.list(PlaylistAccessService.requireUserId(), limit));
    }

    /**
     * 删除一段对话：取消其待确认任务、清空消息与摘要、移除会话行。
     *
     * <p>{@code chatId} 不属于当前用户时返回 404 —— 归属校验在服务层，
     * 路径参数只是「我想要哪一段」，不构成任何授权。
     */
    @DeleteMapping("/{chatId}")
    public Result<String> delete(@PathVariable String chatId) {
        conversations.delete(PlaylistAccessService.requireUserId(), chatId);
        return Result.success("会话已删除");
    }
}
