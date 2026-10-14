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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;

@Service
@Slf4j
public class AiServiceImpl extends ServiceImpl<AiMapper,ChatMessage> implements IAiService {

    /**
     * 标题截断长度。{@code ai_chat_state.title} 是 VARCHAR(64)，留出省略号与多字节余量。
     * 与 {@code phase7_conversations.sql} 的历史回填（LEFT(content, 60)）保持同一量级。
     */
    static final int TITLE_MAX_CHARS = 60;

    @Override
    @Transactional
    public void acquireConversation(String chatId, String runToken, long leaseSeconds) {
        // 归属由 ConversationService 在 resolve 阶段落好；这里拿不到 userId，
        // 传 null 让 ensureConversation 的 COALESCE 保持既有归属不变。
        baseMapper.ensureConversation(chatId, null, LocalDateTime.now());
        if (baseMapper.acquireConversation(chatId, runToken, leaseSeconds) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前会话正在生成回复，请结束后再操作");
        }
    }

    @Override
    public void releaseConversation(String chatId, String runToken) {
        // 带 token 释放，过期请求不能解锁后来者。
        baseMapper.releaseConversation(chatId, runToken);
    }

    @Override
    public String getModelContext(String chatId) { return baseMapper.getModelContext(chatId); }

    @Override
    @Transactional
    public void appendConversation(String chatId, String runToken, String contextJson, List<ChatMessage> messages) {
        // 条件更新同时取得行锁。追加和 clear 都先锁同一会话行，避免清空后旧回调复活历史。
        if (baseMapper.updateModelContext(chatId, runToken, contextJson) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "会话请求已结束，请重新发送");
        }
        LocalDateTime now = LocalDateTime.now();
        for (ChatMessage message : messages) {
            message.setChatId(chatId);
            message.setCreatedAt(now);
            message.setUpdatedAt(now);
            if (baseMapper.insert(message) != 1) throw new IllegalStateException("保存聊天消息失败");
        }
        // 会话元信息只在这里维护，而且只在消息真的落库之后：
        // 这样"打开面板""请求失败"这类没有产生消息的操作都不会把会话顶到列表最前面。
        baseMapper.touchConversation(chatId, now);
        String title = titleFrom(messages);
        if (title != null) baseMapper.updateTitleIfEmpty(chatId, title);
    }

    /**
     * 从本批消息里取首条用户消息生成标题。
     *
     * <p><b>刻意不调用大模型：</b>标题只是列表里的定位线索，为它多付一次模型调用与等待时间
     * 不划算，而且模型生成的标题往往比用户原话更模糊。直接截断用户原话，
     * 既准确又与 {@code phase7_conversations.sql} 的历史回填规则保持一致。
     *
     * @return 标题；本批没有用户消息时返回 {@code null}（调用方据此跳过写入）
     */
    static String titleFrom(List<ChatMessage> messages) {
        for (ChatMessage message : messages) {
            if (message.getMessageType() != ChatMessage.MessageType.USER) continue;
            String content = message.getContent();
            if (content == null) continue;
            // 换行与连续空白会被列表的省略号渲染得很脏，先压平再截断。
            String flat = content.strip().replaceAll("\\s+", " ");
            if (flat.isEmpty()) continue;
            return flat.length() <= TITLE_MAX_CHARS ? flat
                    : flat.substring(0, TITLE_MAX_CHARS) + "…";
        }
        return null;
    }

    /**
     * 根据聊天ID获取所有消息（按稳定消息ID升序）
     */
    public List<ChatMessage> getMessagesByChatId(String chatId) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getChatId, chatId)
                .orderByAsc(ChatMessage::getId);
        return this.list(wrapper);
    }

    /**
     * 根据聊天ID分页获取消息（按稳定消息ID升序）
     */
    public Page<ChatMessage> getMessagesByChatId(String chatId, int page, int size) {
        Page<ChatMessage> p = new Page<>(page, size);
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getChatId, chatId)
                .orderByAsc(ChatMessage::getId);
        return this.page(p, wrapper);
    }

    /**
     * 获取最近的 N 条消息（按稳定消息ID降序）
     */
    public List<ChatMessage> getRecentMessages(String chatId, int limit) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getChatId, chatId)
                .orderByDesc(ChatMessage::getId)
                .last("limit " + limit);
        return this.list(wrapper);
    }

    /**
     * 删除指定聊天的所有消息
     */
    @Transactional
    public void deleteMessagesByChatId(String chatId) {
        String token = UUID.randomUUID().toString();
        // clear 与生成使用同一个数据库租约；活动请求返回 409，由用户待回复结束后重试。
        acquireConversation(chatId, token, 30);
        if (baseMapper.updateModelContext(chatId, token, "[]") != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "会话状态已改变，请重试");
        }
        // 清空窗口的同时清空摘要：摘要是对话内容的压缩产物，
        // 用户删了聊天记录之后它必须一起消失，否则等于删了记录却留着内容（隐私问题）。
        baseMapper.clearSummary(chatId);
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getChatId, chatId);
        this.remove(wrapper);
        baseMapper.releaseConversation(chatId, token);
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
