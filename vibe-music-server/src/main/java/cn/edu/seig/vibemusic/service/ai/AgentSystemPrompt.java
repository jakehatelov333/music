package cn.edu.seig.vibemusic.service.ai;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行时组装系统提示词。
 *
 * <p><b>为什么要抽成接口而不是直接用实现类：</b>评测基座（{@code AgentEvalHarness}）的
 * 评测用例必须保持"只有基础提示词"的旧行为，否则画像一改动评测基线就整体漂移、
 * 失去回归价值。用 {@link StaticSystemPrompt} 把它钉住，个性化改动就不会波及评测。
 *
 * <p><b>{@code chatId} 现在用不到但必须留在签名里：</b>摘要记忆（阶段二剩余的另一半）
 * 需要按会话读取滚动摘要并注入，届时不必再改接口和所有调用点。
 */
public interface AgentSystemPrompt {

    /**
     * @param userId 服务端认证得到的用户 ID；游客为 {@code null}
     * @param chatId 当前会话 ID
     * @return 完整的系统提示词，非 null
     */
    String build(Long userId, String chatId);

    /**
     * 组装并<b>如实报告</b>每个动态块是否真的注入。
     *
     * <p>追踪需要回答"这次为什么没有个性化"：是开关关了、数据为空，还是注入抛错被跳过。
     * 这些事实只有组装者自己知道，事后从字符串里猜（比如看块标题在不在）会把
     * "数据为空"和"注入失败"混为一谈。默认实现只报告基础提示词，
     * 个性化实现覆盖它给出逐块状态。
     */
    default Assembly assemble(Long userId, String chatId) {
        String prompt = build(userId, chatId);
        Map<String, Object> blocks = new LinkedHashMap<>();
        blocks.put("base", "INJECTED");
        blocks.put("summary", "NOT_APPLICABLE");
        blocks.put("profile", "NOT_APPLICABLE");
        blocks.put("manualPreference", "NOT_APPLICABLE");
        return new Assembly(prompt, blocks);
    }

    /**
     * @param prompt 最终提示词（基础 + 已成功注入的动态块）
     * @param blocks 动态块状态：INJECTED / SKIPPED_EMPTY / SKIPPED_DISABLED / FAILED / NOT_APPLICABLE
     */
    record Assembly(String prompt, Map<String, Object> blocks) {
    }
}
