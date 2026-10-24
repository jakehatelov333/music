package cn.edu.seig.vibemusic.service.agent.trace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Trace 采集与保留策略。
 *
 * <p><b>为什么采集开关与长度上限要可配置：</b>完整提示词、模型输入与工具返回都可能含
 * 用户隐私，也可能很长（一次工具返回几十首歌）。默认保留足以排障的摘要，同时提供
 * 硬上限，避免单条记录撑爆表；需要复现时可以把上限调大，关闭采集则只留元数据。
 *
 * <p><b>不提供"静默截断"：</b>任何被截断或关闭的内容，都会在 {@code snapshot_state}
 * 与 {@code snapshot_note} 里写明，界面必须一并展示。
 */
@Component
public class AgentTraceSettings {

    @Value("${ai.trace.enabled:true}")
    private boolean enabled;

    /** 是否保存消息正文、模型输出、工具返回等原始内容。关闭后只保留元数据与统计。 */
    @Value("${ai.trace.capture-content:true}")
    private boolean captureContent;

    /** 单条消息 / 单段文本的字符上限。 */
    @Value("${ai.trace.max-text-chars:4000}")
    private int maxTextChars;

    /**
     * 系统提示词的字符上限，刻意比单条消息大。
     *
     * <p><b>为什么单独一个上限：</b>基础提示词本身就有数千字，是排障时最需要完整保留的内容；
     * 用单条消息的上限去截它，等于每次记录都丢掉后半段规则 —— 那恰恰是"模型为什么这样回答"
     * 的关键证据。动态块（摘要 / 画像 / 手动偏好）也在这里，必须一起看才完整。
     */
    @Value("${ai.trace.max-system-prompt-chars:40000}")
    private int maxSystemPromptChars;

    /** 单次模型调用输入快照（消息列表整体）的字符上限。 */
    @Value("${ai.trace.max-messages-chars:60000}")
    private int maxMessagesChars;

    /** 工具返回数据的字符上限。 */
    @Value("${ai.trace.max-result-chars:20000}")
    private int maxResultChars;

    /**
     * 工具定义快照的字符上限。
     *
     * <p>本项目有 10 个工具，每个都带较长的中文描述与参数说明，整份定义超过一万字符是正常的。
     * 上限设得太小会让每次记录都变成"截断"，反而掩盖真正超长的情况。
     */
    @Value("${ai.trace.max-tools-chars:60000}")
    private int maxToolsChars;

    /** 保留天数，超期由 {@code AiTraceRetentionTask} 清理。0 或负数表示不清理。 */
    @Value("${ai.trace.retention-days:30}")
    private int retentionDays;

    public boolean enabled() { return enabled; }

    public boolean captureContent() { return captureContent; }

    public int maxTextChars() { return Math.max(200, maxTextChars); }

    public int maxSystemPromptChars() { return Math.max(1000, maxSystemPromptChars); }

    public int maxMessagesChars() { return Math.max(1000, maxMessagesChars); }

    public int maxResultChars() { return Math.max(200, maxResultChars); }

    public int maxToolsChars() { return Math.max(1000, maxToolsChars); }

    public int retentionDays() { return retentionDays; }
}
