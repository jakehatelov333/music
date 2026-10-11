package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.mapper.AiMapper;
import cn.edu.seig.vibemusic.model.entity.ChatMessage;
import cn.edu.seig.vibemusic.service.IAiService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
public class AiServiceImpl extends ServiceImpl<AiMapper,ChatMessage> implements IAiService {

    // 使用 ServiceImpl 提供的 baseMapper / save 方法进行持久化

    @Transactional
    public ChatMessage saveMessage(String chatId, ChatMessage.MessageType messageType, String content) {
        ChatMessage message = ChatMessage.builder()
                .chatId(chatId)
                .messageType(messageType)
                .content(content)
                .build();

        // 使用 MyBatis-Plus 保存
        boolean saved = this.save(message);
        if (saved) {
            log.info("保存聊天消息成功，聊天ID: {}, 消息类型: {}, 消息ID: {}", chatId, messageType, message.getId());
            return message;
        }
        throw new RuntimeException("保存聊天消息失败");
    }

    /**
     * 根据聊天ID获取所有消息（按创建时间升序）
     */
    public List<ChatMessage> getMessagesByChatId(String chatId) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getChatId, chatId)
                .orderByAsc(ChatMessage::getCreatedAt);
        return this.list(wrapper);
    }

    /**
     * 根据聊天ID分页获取消息（按创建时间升序）
     */
    public Page<ChatMessage> getMessagesByChatId(String chatId, int page, int size) {
        Page<ChatMessage> p = new Page<>(page, size);
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getChatId, chatId)
                .orderByAsc(ChatMessage::getCreatedAt);
        return this.page(p, wrapper);
    }

    /**
     * 获取最近的 N 条消息（按创建时间降序）
     */
    public List<ChatMessage> getRecentMessages(String chatId, int limit) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getChatId, chatId)
                .orderByDesc(ChatMessage::getCreatedAt)
                .last("limit " + limit);
        return this.list(wrapper);
    }

    /**
     * 删除指定聊天的所有消息
     */
    @Transactional
    public void deleteMessagesByChatId(String chatId) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getChatId, chatId);
        this.remove(wrapper);
        log.info("删除聊天消息成功，聊天ID: {}", chatId);
    }

    /**
     * 统计聊天消息数量
     */
    public long countMessagesByChatId(String chatId) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getChatId, chatId);
        return this.count(wrapper);
    }

}
