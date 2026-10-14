package cn.edu.seig.vibemusic.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 会话列表中的一行。用于「最近对话」抽屉，不包含任何消息正文。
 *
 * <p><b>两个「摘要」字段是刻意的，不是一个：</b>
 * <ul>
 *     <li>{@code summaryText} 是滚动摘要（{@code ai_chat_state.summary_text}），
 *         描述的是这段对话<b>整体</b>在聊什么，可能为空（对话还不够长）；</li>
 *     <li>{@code lastMessage} 是最近一条用户/助手消息的截断，永远有值（有消息时）。</li>
 * </ul>
 * 前端优先展示 {@code summaryText}，为空时回退到 {@code lastMessage} ——
 * 单看任意一个都会在某种情况下出现空白行。
 */
@Data
public class ConversationVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 会话标识。客户端后续请求必须原样带回，服务端会校验归属。 */
    private String chatId;
    /** 标题：取自首条用户消息的截断，可能为空（极早期数据）。 */
    private String title;
    /** 滚动摘要，可能为空。 */
    private String summaryText;
    /** 最近一条用户/助手消息的截断，用于摘要为空时兜底。 */
    private String lastMessage;
    private LocalDateTime createdAt;
    private LocalDateTime lastActiveAt;
}
