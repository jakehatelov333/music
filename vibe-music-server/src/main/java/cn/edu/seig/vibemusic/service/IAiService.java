package cn.edu.seig.vibemusic.service;

import cn.edu.seig.vibemusic.model.entity.ChatMessage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;

/**
 * ai 服务接口
 */
public interface IAiService extends IService<ChatMessage> {
    /**
     * 保存消息
     * @param chatId
     * @param messageType
     * @param query
     */
    ChatMessage saveMessage(String chatId, ChatMessage.MessageType messageType, String query);

    /**
     * 获取聊天历史记录（按时间升序）
     */
    List<ChatMessage> getMessagesByChatId(String chatId);

    /**
     * 分页获取聊天历史记录（按时间升序）
     */
    Page<ChatMessage> getMessagesByChatId(String chatId, int page, int size);

    /**
     * 获取最近的 N 条消息（按时间降序，返回时可直接用于展示或再行排序）
     */
    List<ChatMessage> getRecentMessages(String chatId, int limit);

    /**
     * 清空某个会话聊天记录
     */
    void deleteMessagesByChatId(String chatId);

    /**
     * 统计某个会话消息数量
     */
    long countMessagesByChatId(String chatId);
}
