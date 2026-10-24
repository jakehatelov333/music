package cn.edu.seig.vibemusic.service.ai;

import cn.edu.seig.vibemusic.service.MusicAssistant;

/**
 * 只返回基础提示词，不注入任何用户数据。
 *
 * <p>用途有两个：
 * <ol>
 *     <li><b>评测基座</b>：让离线评测用例的行为与引入个性化之前完全一致，
 *         评测基线不会因为画像功能上线而漂移；</li>
 *     <li><b>不关心个性化的场景</b>：需要"裸"提示词做对照时直接用它。</li>
 * </ol>
 */
public final class StaticSystemPrompt implements AgentSystemPrompt {

    @Override
    public String build(Long userId, String chatId) {
        return MusicAssistant.SYSTEM_PROMPT;
    }
}
