package cn.edu.seig.vibemusic.controller;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.model.entity.ChatMessage;
import cn.edu.seig.vibemusic.service.IAiService;
import cn.edu.seig.vibemusic.service.MusicAssistant;
import cn.edu.seig.vibemusic.service.MusicAssistantNoTools;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.dashscope.QwenChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.TokenStream;
import jakarta.servlet.http.Cookie;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Slf4j
@RestController
@RequestMapping("/test")
public class AiController {

    @Autowired
    private IAiService aiService;

    @Autowired
    private MusicAssistant musicAssistant;

    @Autowired
    private MusicAssistantNoTools musicAssistantNoTools;

    @Autowired
    private QwenChatModel qwenChatModel;

    /**
     * 直接调用 QwenChatModel（不使用 AiServices、Memory、Tools），用于隔离问题
     */
    @GetMapping("/direct/chat")
    public String directChat(@RequestParam("query") String query) {
        log.info("直接调用 QwenChatModel，query={}", query);
        var messages = List.of(
                dev.langchain4j.data.message.SystemMessage.systemMessage("你是一个音乐助手，请用中文简洁回答。"),
                UserMessage.from(query)
        );
        var response = qwenChatModel.generate(messages);
        return response.content().text();
    }

    public Long getUserId() {
        try {
            Map<String, Object> claims = ThreadLocalUtil.get();
            if (claims != null) {
                Object userIdObj = claims.get(JwtClaimsConstant.USER_ID);
                if (userIdObj != null) {
                    // 确保转换为Long类型
                    return userIdObj instanceof Long ? (Long) userIdObj : Long.valueOf(userIdObj.toString());
                }
            }
        } catch (Exception e) {
            log.error("从ThreadLocalUtil获取用户ID失败", e);
        }
        
        return null;
    }


    private String resolveChatIdForGuest(Long userId, HttpServletRequest request, HttpServletResponse response) {
        // 如果是登录用户，直接返回
        if (userId != null) {
            return "user_chat_id_" + userId;
        }

        // 检查Cookie中是否有游客对话ID
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("guest_chat_id".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }

        // 生成新的游客对话ID并设置Cookie
        String newGuestId = "guest_" + System.currentTimeMillis() + "_" + Math.random();
        Cookie guestCookie = new Cookie("guest_chat_id", newGuestId);
        guestCookie.setMaxAge(24 * 60 * 1); // 1小时有效期
        guestCookie.setPath("/");
        response.addCookie(guestCookie);

        return newGuestId;
    }


    @GetMapping("/simple/chat")
    public String simpleChat(@RequestParam("query") String query,
                             HttpServletRequest request,
                             HttpServletResponse response) {
        Long userId = getUserId();
        String chatId = resolveChatIdForGuest(userId, request, response);
        log.info("用户消息，角色: {}, 聊天ID: {}, 消息: {}", userId == null ? "游客" : "用户" + userId, chatId, query);
        return musicAssistant.chat(chatId, query);
    }

    /**
     * 流式对话（SSE），TokenStream 逐字返回，前端实时渲染
     */
    @GetMapping(value = "/simple/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestParam("query") String query,
                                  HttpServletRequest request,
                                  HttpServletResponse response) {
        Long userId = getUserId();
        String chatId = resolveChatIdForGuest(userId, request, response);
        log.info("流式对话开始，角色: {}, 聊天ID: {}, 消息: {}",
                userId == null ? "游客" : "用户" + userId, chatId, query);

        SseEmitter emitter = new SseEmitter(120_000L);
        TokenStream tokenStream = musicAssistant.chatStream(chatId, query);

        tokenStream
            .onNext(token -> {
                try {
                    emitter.send(SseEmitter.event().name("token").data(token));
                } catch (IOException e) {
                    log.error("SSE 发送 token 失败", e);
                    emitter.completeWithError(e);
                }
            })
            .onComplete(responseMessage -> {
                try {
                    String fullText = responseMessage.content().text();
                    emitter.send(SseEmitter.event().name("done").data(fullText));
                    emitter.complete();
                    log.info("流式对话完成，chatId={}, 响应长度={}",
                            chatId, fullText != null ? fullText.length() : 0);
                } catch (IOException e) {
                    log.error("SSE 完成事件发送失败", e);
                    emitter.completeWithError(e);
                }
            })
            .onError(error -> {
                log.error("TokenStream 错误，chatId={}", chatId, error);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(error.getMessage() != null ? error.getMessage() : "AI 回复出错"));
                } catch (IOException ignored) {
                }
                emitter.completeWithError(error);
            })
            .start();

        emitter.onTimeout(() -> log.warn("SSE 超时，chatId={}", chatId));
        emitter.onError(ex -> log.error("SSE 连接错误，chatId={}", chatId, ex));

        return emitter;
    }

    /**
     * 不使用工具的简单对话（用于隔离问题：判断 InputRequiredException 是否由 tools 引起）
     */
    @GetMapping("/simple/chat-notools")
    public String simpleChatNoTools(@RequestParam("query") String query,
                                    HttpServletRequest request,
                                    HttpServletResponse response) {
        Long userId = getUserId();
        String chatId = resolveChatIdForGuest(userId, request, response);
        log.info("无工具对话，角色: {}, 聊天ID: {}, 消息: {}", userId == null ? "游客" : "用户" + userId, chatId, query);
        return musicAssistantNoTools.chat(chatId, query);
    }

    /**
     * 获取聊天历史记录
     */
    @GetMapping("/chat/history")
    public List<ChatMessage> getChatHistory(@RequestParam("chatId") String chatId,
                                            HttpServletRequest request,
                                            HttpServletResponse response) {
        Long userId = getUserId();
        String effectiveChatId = resolveChatIdForGuest(userId, request, response);
        return aiService.getMessagesByChatId(effectiveChatId);
    }

    /**
     * 分页获取聊天历史记录
     */
    @GetMapping("/chat/history/page")
    public List<ChatMessage> getChatHistoryPage(@RequestParam("chatId") String chatId,
                                                @RequestParam(value = "page", defaultValue = "1") int page,
                                                @RequestParam(value = "size", defaultValue = "10") int size,
                                                HttpServletRequest request,
                                                HttpServletResponse response) {
        Long userId = getUserId();
        String effectiveChatId = resolveChatIdForGuest(userId, request, response);
        return aiService.getMessagesByChatId(effectiveChatId, page, size).getRecords();
    }

    /**
     * 获取最近的N条消息
     */
    @GetMapping("/chat/recent")
    public List<ChatMessage> getRecentMessages(@RequestParam("chatId") String chatId,
                                               @RequestParam(value = "limit", defaultValue = "10") int limit,
                                               HttpServletRequest request,
                                               HttpServletResponse response) {
        Long userId = getUserId();
        String effectiveChatId = resolveChatIdForGuest(userId, request, response);
        return aiService.getRecentMessages(effectiveChatId, limit);
    }

    /**
     * 清空聊天记录
     */
    @DeleteMapping("/chat/clear")
    public String clearChatHistory(@RequestParam("chatId") String chatId,
                                   HttpServletRequest request,
                                   HttpServletResponse response) {
        Long userId = getUserId();
        String effectiveChatId = resolveChatIdForGuest(userId, request, response);
        aiService.deleteMessagesByChatId(effectiveChatId);
        return "聊天记录已清空";
    }

    /**
     * 获取聊天统计信息
     */
    @GetMapping("/chat/stats")
    public String getChatStats(@RequestParam("chatId") String chatId,
                               HttpServletRequest request,
                               HttpServletResponse response) {
        Long userId = getUserId();
        String effectiveChatId = resolveChatIdForGuest(userId, request, response);
        long messageCount = aiService.countMessagesByChatId(effectiveChatId);
        return "聊天ID: " + effectiveChatId + ", 消息总数: " + messageCount;
    }
}
