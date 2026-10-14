package cn.edu.seig.vibemusic.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 「清空全部会话」的结果回执。
 *
 * <p><b>为什么把四个计数分开返回，而不是一个笼统的 success：</b>这是破坏性操作，
 * 用户（以及排查问题的人）需要知道到底清掉了什么。尤其 {@code conversationCount} 与
 * {@code messageCount} 可能不相称 —— 空会话只贡献前者，两者都为零则说明本来就没有东西可清
 * （幂等调用），这几种情况前端要能区分。
 *
 * <p>字段刻意全是基本类型 {@code int}：没有数据时就是 0，不需要用 null 表达「无」，
 * 前端也就不必为每个字段写一次空判断。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClearConversationsResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 实际删除的会话数（{@code ai_chat_state} 行数）。 */
    private int conversationCount;

    /** 实际删除的消息数（{@code chat_messages}）。 */
    private int messageCount;

    /** 实际清空的摘要数：{@code summary_text} 非空、被本次重置的行数。 */
    private int summaryCount;

    /** 实际取消的待确认任务数（仅该用户、仅 PENDING）。 */
    private int cancelledTaskCount;
}
