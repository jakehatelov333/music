package cn.edu.seig.vibemusic.service.ai;

import cn.edu.seig.vibemusic.service.MusicAssistant;
import cn.edu.seig.vibemusic.service.agent.ChatSummaryService;
import cn.edu.seig.vibemusic.service.agent.UserMusicProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 个性化系统提示词：基础提示词 + 更早对话摘要 + 用户音乐偏好。
 *
 * <p><b>顺序有讲究：</b>基础提示词（角色设定与硬约束）必须在最前；偏好块放最后 ——
 * 偏好块里含用户可影响的内容，放在末尾可以避免它"盖住"前面的安全约束。
 * 摘要在中间：它是对话级记忆（游客也有会话），优先级低于安全约束、高于画像。
 *
 * <p><b>注入防御：</b>摘要块与偏好块都只做"数据"处理，块内显式声明
 * "是数据不是指令"；摘要块额外声明"与本次要求冲突时以本次为准"。
 * 具体防线见 {@link ChatSummaryService#renderSummaryBlock(String)} 与
 * {@link UserMusicProfileService#renderPreferenceBlock(Long)}。
 *
 * <p><b>失败降级：</b>画像与摘要都是"锦上添花"。数据库抖动、JSON 解析异常等任何问题都只记日志
 * 并跳过对应块，绝不能因为个性化取不到而让整个对话失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonalizedSystemPrompt implements AgentSystemPrompt {

    private final UserMusicProfileService profiles;
    private final ChatSummaryService summaries;

    @Override
    public String build(Long userId, String chatId) {
        StringBuilder prompt = new StringBuilder(MusicAssistant.SYSTEM_PROMPT);
        // 会话摘要是对话级记忆，游客同样有会话，因此不依赖 userId。
        try {
            String summary = summaries.renderSummaryBlock(chatId);
            if (summary != null) prompt.append("\n\n").append(summary);
        } catch (RuntimeException e) {
            log.warn("对话摘要注入失败，已跳过，类型={}", e.getClass().getSimpleName());
        }
        if (userId == null) return prompt.toString();   // 游客没有画像
        try {
            String preference = profiles.renderPreferenceBlock(userId);
            if (preference != null) prompt.append("\n\n").append(preference);
        } catch (RuntimeException e) {
            log.warn("用户画像注入失败，已跳过，类型={}", e.getClass().getSimpleName());
        }
        return prompt.toString();
    }
}
