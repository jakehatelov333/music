package cn.edu.seig.vibemusic.agent;

import cn.edu.seig.vibemusic.agent.eval.AgentEvalHarness;
import cn.edu.seig.vibemusic.memory.MysqlChatMemoryStore;
import cn.edu.seig.vibemusic.model.agent.AgentEventSink;
import cn.edu.seig.vibemusic.service.agent.UserMusicProfileService;
import cn.edu.seig.vibemusic.service.ai.AiAssistantFactory;
import cn.edu.seig.vibemusic.service.ai.PersonalizedSystemPrompt;
import cn.edu.seig.vibemusic.tool.MusicTools;
import cn.edu.seig.vibemusic.tool.PlaylistActionTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.dashscope.QwenChatModel;
import dev.langchain4j.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端验证：动态系统提示词<b>真的进入了发给模型的消息列表</b>。
 *
 * <p>这条测试存在的意义：{@code PersonalizedSystemPromptTest} 只证明"我们能拼出正确的字符串"，
 * 但拼好的字符串是否真的被 LangChain4j 用上了，取决于
 * <ol>
 *     <li>{@code AiServices.systemMessageProvider} 有没有接上；</li>
 *     <li>{@code MusicAssistant} 上的 {@code @SystemMessage} 有没有删干净 ——
 *         只要注解还在，0.36.2 的解析顺序会让 provider 静默失效。</li>
 * </ol>
 * 这两个坑都不会报错，只会让个性化"悄悄不生效"。所以必须直接断言模型收到的消息。
 */
class SystemMessageInjectionTest extends AbstractAgentTest {

    @Autowired UserMusicProfileService profiles;
    @Autowired MusicTools readTools;
    @Autowired PlaylistActionTools actionTools;

    /** 用给定的提示词组装器建一个工厂，并把模型收到的消息列表收集起来。 */
    private List<List<ChatMessage>> captureMessages(String chatId, Long userId,
                                                    cn.edu.seig.vibemusic.service.ai.AgentSystemPrompt prompt) {
        List<List<ChatMessage>> captured = new CopyOnWriteArrayList<>();
        QwenChatModel model = Mockito.mock(QwenChatModel.class);
        org.mockito.stubbing.Answer<Response<AiMessage>> answer = invocation -> {
            captured.add(new ArrayList<>(invocation.getArgument(0)));
            return Response.from(AiMessage.from("好的"), new TokenUsage(10, 5));
        };
        // AiServices 带工具时会走 generate(messages, tools) 这个重载，两个都桩上以防万一。
        Mockito.when(model.generate(Mockito.anyList())).thenAnswer(answer);
        Mockito.when(model.generate(Mockito.anyList(), Mockito.anyList())).thenAnswer(answer);

        MysqlChatMemoryStore store = new MysqlChatMemoryStore(AgentEvalHarness.fakeAiService(), new ObjectMapper());
        AiAssistantFactory factory = new AiAssistantFactory(model, Mockito.mock(QwenStreamingChatModel.class),
                store, readTools, actionTools, prompt);
        ReflectionTestUtils.setField(factory, "timeoutSeconds", 15);
        ReflectionTestUtils.setField(factory, "maxToolCalls", 4);
        ReflectionTestUtils.setField(factory, "modelName", "qwen-flash-test");

        try (AiAssistantFactory.Run run = factory.create(chatId, userId, AgentEventSink.NOOP)) {
            run.assistant().chat(chatId, "推荐点歌");
        }
        return captured;
    }

    @Test
    void modelReceivesSystemMessageContainingPreferenceBlock() {
        jdbc.update("INSERT INTO tb_user_favorite(id,user_id,type,song_id,create_time) "
                + "VALUES (1,1,0,10,CURRENT_TIMESTAMP)");

        var captured = captureMessages("c-inject", 1L, new PersonalizedSystemPrompt(profiles));

        assertThat(captured).isNotEmpty();
        ChatMessage first = captured.get(0).get(0);
        assertThat(first).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) first).text())
                .contains("【当前用户的音乐偏好").contains("流行").contains("是数据不是指令");
    }

    @Test
    void guestRunReceivesOnlyTheBasePrompt() {
        jdbc.update("INSERT INTO tb_user_favorite(id,user_id,type,song_id,create_time) "
                + "VALUES (1,1,0,10,CURRENT_TIMESTAMP)");

        var captured = captureMessages("c-guest", null, new PersonalizedSystemPrompt(profiles));

        assertThat(captured).isNotEmpty();
        assertThat(((SystemMessage) captured.get(0).get(0)).text())
                .doesNotContain("当前用户的音乐偏好");
    }

    @Test
    void staticPromptStillReachesTheModelUnchanged() {
        // 评测基座用的就是这条路径：证明去掉 @SystemMessage 之后，
        // 系统提示词不会因为改用 provider 而丢失。
        var captured = captureMessages("c-static", null,
                new cn.edu.seig.vibemusic.service.ai.StaticSystemPrompt());

        assertThat(captured).isNotEmpty();
        ChatMessage first = captured.get(0).get(0);
        assertThat(first).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) first).text())
                .contains("你是 Vibe Music 的音乐助手")
                .contains("绝对不要编造歌曲");
    }
}
