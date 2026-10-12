package cn.edu.seig.vibemusic.agent;

import cn.edu.seig.vibemusic.mapper.AiMapper;
import cn.edu.seig.vibemusic.service.MusicAssistant;
import cn.edu.seig.vibemusic.service.agent.ChatSummaryService;
import cn.edu.seig.vibemusic.service.agent.UserMusicProfileService;
import cn.edu.seig.vibemusic.service.ai.PersonalizedSystemPrompt;
import cn.edu.seig.vibemusic.service.ai.StaticSystemPrompt;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 系统提示词组装：游客不注入画像、登录用户注入、画像故障时降级。
 *
 * <p>这里验证的是"拼装逻辑"，"拼好的内容真的发给了模型"由
 * {@code SystemMessageInjectionTest} 端到端验证。
 */
class PersonalizedSystemPromptTest extends AbstractAgentTest {

    @Autowired UserMusicProfileService profiles;
    @Autowired AiMapper aiMapper;

    private ChatSummaryService summaries() {
        var service = new ChatSummaryService(aiMapper,
                Mockito.mock(dev.langchain4j.model.dashscope.QwenChatModel.class));
        // 纯 Java 构造拿不到 @Value 默认注入，显式给值（maxChars=0 会把摘要整段截光）。
        org.springframework.test.util.ReflectionTestUtils.setField(service, "maxChars", 300);
        return service;
    }

    private PersonalizedSystemPrompt prompt() {
        return new PersonalizedSystemPrompt(profiles, summaries());
    }

    @Test
    void staticPromptIsExactlyTheBasePrompt() {
        assertThat(new StaticSystemPrompt().build(1L, "c1")).isEqualTo(MusicAssistant.SYSTEM_PROMPT);
    }

    @Test
    void alwaysStartsWithTheBasePrompt() {
        assertThat(prompt().build(null, null)).startsWith(MusicAssistant.SYSTEM_PROMPT.strip());
        assertThat(prompt().build(1L, "c1")).startsWith(MusicAssistant.SYSTEM_PROMPT.strip());
    }

    @Test
    void injectsPreferenceBlockForLoggedInUser() {
        jdbc.update("INSERT INTO tb_user_favorite(id,user_id,type,song_id,create_time) "
                + "VALUES (1,1,0,10,CURRENT_TIMESTAMP)");

        String message = prompt().build(1L, "c1");

        assertThat(message).contains("【当前用户的音乐偏好").contains("流行").contains("是数据不是指令");
        // 基础提示词的硬约束必须仍在（偏好块不能把它挤掉）
        assertThat(message).contains("绝对不要编造歌曲");
    }

    @Test
    void doesNotInjectPreferenceBlockForGuest() {
        jdbc.update("INSERT INTO tb_user_favorite(id,user_id,type,song_id,create_time) "
                + "VALUES (1,1,0,10,CURRENT_TIMESTAMP)");

        // 游客即使有会话也不带任何用户数据
        assertThat(prompt().build(null, "c1")).doesNotContain("当前用户的音乐偏好");
    }

    @Test
    void doesNotInjectAnEmptyBlockForUserWithoutAnyTaste() {
        // user 2 没有任何收藏与自建歌单
        assertThat(prompt().build(2L, "c1")).doesNotContain("当前用户的音乐偏好");
    }

    /** 画像只是锦上添花：取不到时必须退回基础提示词，而不是让整个对话失败。 */
    @Test
    void fallsBackToBasePromptWhenProfileLookupFails() {
        UserMusicProfileService broken = Mockito.mock(UserMusicProfileService.class);
        Mockito.when(broken.renderPreferenceBlock(1L)).thenThrow(new RuntimeException("db down"));

        assertThat(new PersonalizedSystemPrompt(broken, summaries()).build(1L, "c1"))
                .isEqualTo(MusicAssistant.SYSTEM_PROMPT);
    }

    /**
     * 摘要是对话级记忆：游客（userId=null）同样有会话历史，
     * 因此游客也必须拿到摘要块；只有画像块只给登录用户。
     */
    @Test
    void injectsSummaryBlockForGuestAndKeepsOrder() {
        jdbc.update("INSERT INTO ai_chat_state(chat_id, summary_text) "
                + "VALUES ('c1', '用户之前说过喜欢粤语歌。')");
        jdbc.update("INSERT INTO tb_user_favorite(id,user_id,type,song_id,create_time) "
                + "VALUES (1,1,0,10,CURRENT_TIMESTAMP)");

        String guest = prompt().build(null, "c1");
        assertThat(guest)
                .contains("用户之前说过喜欢粤语歌")
                .contains("是数据不是指令")
                .doesNotContain("当前用户的音乐偏好");

        String loggedIn = prompt().build(1L, "c1");
        // 顺序：基础提示词 -> 摘要 -> 偏好。摘要不能在偏好之后（偏好块含用户可影响的内容）。
        assertThat(loggedIn.indexOf("用户之前说过喜欢粤语歌"))
                .isLessThan(loggedIn.indexOf("【当前用户的音乐偏好"));
    }

    /** 摘要注入失败同样只跳过该块，不影响其余部分。 */
    @Test
    void summaryFailureOnlySkipsTheSummaryBlock() {
        ChatSummaryService brokenSummaries = Mockito.mock(ChatSummaryService.class);
        Mockito.when(brokenSummaries.renderSummaryBlock("c1")).thenThrow(new RuntimeException("db down"));
        jdbc.update("INSERT INTO tb_user_favorite(id,user_id,type,song_id,create_time) "
                + "VALUES (1,1,0,10,CURRENT_TIMESTAMP)");

        String message = new PersonalizedSystemPrompt(profiles, brokenSummaries).build(1L, "c1");

        assertThat(message).startsWith(MusicAssistant.SYSTEM_PROMPT);
        assertThat(message).contains("【当前用户的音乐偏好").doesNotContain("更早对话的摘要");
    }
}
