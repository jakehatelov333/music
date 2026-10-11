package cn.edu.seig.vibemusic.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * 聊天消息实体类
 */
@TableName("chat_messages")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessage {

    @TableId(value = "id",type = IdType.AUTO)
    private Long id;

    /**
     * 聊天会话ID
     */
    @TableField("chat_id")
    private String chatId;

    /**
     * 消息类型：USER-用户消息，ASSISTANT-AI助手消息
     */
    @TableField("message_type")
    private MessageType messageType;

    /**
     * 消息内容
     */
    @TableField("content")
    private String content;

    /**
     * 消息创建时间
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 消息更新时间
     */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 消息类型枚举
     */
    public enum MessageType {
        USER("用户消息"),
        ASSISTANT("AI助手消息"),
        SYSTEM("系统消息"),
        TOOL_REQUEST("工具调用请求"),
        TOOL_RESULT("工具调用结果");

        private final String description;

        MessageType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}
