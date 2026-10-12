package cn.edu.seig.vibemusic.service;

import cn.edu.seig.vibemusic.model.entity.ChatMessage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;

/**
 * ai 服务接口
 */
public interface IAiService extends IService<ChatMessage> {
    /** 数据库租约在整个生成过程中生效；同会话的并发生成/清空返回 409。 */
    void acquireConversation(String chatId, String runToken, long leaseSeconds);

    void releaseConversation(String chatId, String runToken);

    /** null 表示尚未建立模型窗口，需要从已有历史初始化一次。 */
    String getModelContext(String chatId);

    /** 只追加本次新消息，窗口更新和历史追加在同一事务中提交。 */
    void appendConversation(String chatId, String runToken, String contextJson, List<ChatMessage> messages);

    /**
     * 获取完整聊天历史（按稳定消息 ID 升序，同秒的消息也有确定顺序）
     */
    List<ChatMessage> getMessagesByChatId(String chatId);

    /**
     * 分页获取完整聊天历史（按稳定消息 ID 升序）
     */
    Page<ChatMessage> getMessagesByChatId(String chatId, int page, int size);

    /**
     * 获取最近的 N 条消息（按稳定消息 ID 降序）
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
