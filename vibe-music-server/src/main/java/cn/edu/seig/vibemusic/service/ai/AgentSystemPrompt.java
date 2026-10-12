package cn.edu.seig.vibemusic.service.ai;

/**
 * 运行时组装系统提示词。
 *
 * <p><b>为什么要抽成接口而不是直接用实现类：</b>评测基座（{@code AgentEvalHarness}）的
 * 46 条用例必须保持"只有基础提示词"的旧行为，否则画像一改动评测基线就整体漂移、
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
}
