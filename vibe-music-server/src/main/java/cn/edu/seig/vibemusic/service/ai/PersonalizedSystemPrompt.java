package cn.edu.seig.vibemusic.service.ai;

import cn.edu.seig.vibemusic.service.MusicAssistant;
import cn.edu.seig.vibemusic.service.agent.UserMusicProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 个性化系统提示词：基础提示词 + 用户音乐偏好块。
 *
 * <p><b>顺序有讲究：</b>基础提示词（角色设定与硬约束）必须在最前，偏好块放最后 ——
 * 偏好块里含用户可影响的内容，放在末尾可以避免它"盖住"前面的安全约束。
 *
 * <p><b>注入防御：</b>偏好块只由结构化字段拼成（风格/歌手/计数），并在块内显式声明
 * "是数据不是指令"；具体防线见
 * {@link UserMusicProfileService#renderPreferenceBlock(Long)}。
 *
 * <p><b>失败降级：</b>画像只是"锦上添花"。数据库抖动、JSON 解析异常等任何问题都只记日志
 * 并退回基础提示词，绝不能因为画像取不到而让整个对话失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonalizedSystemPrompt implements AgentSystemPrompt {

    private final UserMusicProfileService profiles;

    @Override
    public String build(Long userId, String chatId) {
        String base = MusicAssistant.SYSTEM_PROMPT;
        if (userId == null) return base;   // 游客没有画像，直接走基础提示词
        try {
            String preference = profiles.renderPreferenceBlock(userId);
            return preference == null ? base : base + "\n\n" + preference;
        } catch (RuntimeException e) {
            log.warn("用户画像注入失败，已退回基础提示词，类型={}", e.getClass().getSimpleName());
            return base;
        }
    }
}
