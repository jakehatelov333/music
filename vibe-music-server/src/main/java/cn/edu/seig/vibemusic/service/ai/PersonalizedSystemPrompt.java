package cn.edu.seig.vibemusic.service.ai;

import cn.edu.seig.vibemusic.service.MusicAssistant;
import cn.edu.seig.vibemusic.service.agent.ChatSummaryService;
import cn.edu.seig.vibemusic.service.agent.UserMemoryPreferenceService;
import cn.edu.seig.vibemusic.service.agent.UserMusicProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 个性化系统提示词：基础提示词 + 更早对话摘要 + 用户音乐偏好 + 用户手动偏好。
 *
 * <p><b>顺序有讲究：</b>基础提示词（角色设定与硬约束）必须在最前；偏好块放最后 ——
 * 偏好块里含用户可影响的内容，放在末尾可以避免它"盖住"前面的安全约束。
 * 摘要在中间：它是对话级记忆（游客也有会话），优先级低于安全约束、高于画像。
 * 自动画像块先于手动偏好块：手动偏好是用户主动指定、优先级更高的一层，
 * 放在最后（最贴近对话内容的位置）且其中「硬性排除」被显式声明为必须遵守。
 *
 * <p><b>个性化开关：</b>{@code UserMemoryPreferenceService#isPersonalizationEnabled}
 * 为 false 时，画像块与手动偏好块<b>都不注入</b>（关闭后 AI 不再基于已保存的
 * 画像和偏好做个性化，但仍可响应当前请求）；摘要块照常注入 —— 会话摘要
 * 是对话级记忆，不是长期偏好，两者不混为一谈。
 *
 * <p><b>注入防御：</b>摘要块与偏好块都只做"数据"处理，块内显式声明
 * "是数据不是指令"；摘要块额外声明"与本次要求冲突时以本次为准"。
 * 具体防线见 {@link ChatSummaryService#renderSummaryBlock(String)}、
 * {@link UserMusicProfileService#renderPreferenceBlock(Long)} 与
 * {@link UserMemoryPreferenceService#renderManualPreferenceBlock(Long)}。
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
    private final UserMemoryPreferenceService memory;

    @Override
    public String build(Long userId, String chatId) {
        return assemble(userId, chatId).prompt();
    }

    /**
     * 组装并如实记录每个块的真实结果。
     *
     * <p><b>块状态是"实测事实"，不是"设计意图"：</b>
     * {@code SKIPPED_EMPTY}（没有数据）、{@code SKIPPED_DISABLED}（个性化开关关闭）、
     * {@code FAILED}（注入抛错被跳过）三者对排障意义完全不同，
     * 事后从提示词字符串里看不出来，所以在这里就地记录。
     */
    @Override
    public Assembly assemble(Long userId, String chatId) {
        Map<String, Object> blocks = new LinkedHashMap<>();
        blocks.put("base", "INJECTED");
        StringBuilder prompt = new StringBuilder(MusicAssistant.SYSTEM_PROMPT);

        // 会话摘要是对话级记忆，游客同样有会话，因此不依赖 userId。
        try {
            String summary = summaries.renderSummaryBlock(chatId);
            if (summary != null) {
                prompt.append("\n\n").append(summary);
                blocks.put("summary", "INJECTED");
            } else {
                blocks.put("summary", "SKIPPED_EMPTY");
            }
        } catch (RuntimeException e) {
            log.warn("对话摘要注入失败，已跳过，类型={}", e.getClass().getSimpleName());
            blocks.put("summary", "FAILED");
            blocks.put("summaryError", e.getClass().getSimpleName());
        }

        if (userId == null) {
            // 游客没有画像与手动偏好 —— 这是设计如此，不是失败。
            blocks.put("profile", "NOT_APPLICABLE");
            blocks.put("manualPreference", "NOT_APPLICABLE");
            blocks.put("guest", true);
            return new Assembly(prompt.toString(), blocks);
        }

        try {
            if (!memory.isPersonalizationEnabled(userId)) {
                blocks.put("profile", "SKIPPED_DISABLED");
                blocks.put("manualPreference", "SKIPPED_DISABLED");
                return new Assembly(prompt.toString(), blocks);
            }
        } catch (RuntimeException e) {
            // 开关读取失败按开启处理：宁可少一次个性化，也不静默吞掉用户已配置的偏好。
            log.warn("个性化开关读取失败，按开启处理，类型={}", e.getClass().getSimpleName());
            blocks.put("personalizationSwitch", "FAILED_DEFAULTED_ON");
        }

        try {
            String preference = profiles.renderPreferenceBlock(userId);
            if (preference != null) {
                prompt.append("\n\n").append(preference);
                blocks.put("profile", "INJECTED");
            } else {
                blocks.put("profile", "SKIPPED_EMPTY");
            }
        } catch (RuntimeException e) {
            log.warn("用户画像注入失败，已跳过，类型={}", e.getClass().getSimpleName());
            blocks.put("profile", "FAILED");
            blocks.put("profileError", e.getClass().getSimpleName());
        }
        try {
            String manual = memory.renderManualPreferenceBlock(userId);
            if (manual != null) {
                prompt.append("\n\n").append(manual);
                blocks.put("manualPreference", "INJECTED");
            } else {
                blocks.put("manualPreference", "SKIPPED_EMPTY");
            }
        } catch (RuntimeException e) {
            log.warn("手动偏好注入失败，已跳过，类型={}", e.getClass().getSimpleName());
            blocks.put("manualPreference", "FAILED");
            blocks.put("manualPreferenceError", e.getClass().getSimpleName());
        }
        return new Assembly(prompt.toString(), blocks);
    }
}
