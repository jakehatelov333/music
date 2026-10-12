package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.service.ai.AiRunBudget;
import cn.edu.seig.vibemusic.service.ai.BoundedChatModels;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiBudgetTest {
    private static Response<AiMessage> tools(int count) {
        return Response.from(AiMessage.from(java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> ToolExecutionRequest.builder().id("id" + i).name("searchSongs").arguments("{}").build()).toList()));
    }

    @Test void aParallelBatchCannotExceedToolBudget() {
        AiRunBudget budget = new AiRunBudget(Duration.ofMinutes(1), 2);
        assertThrows(IllegalStateException.class, () -> budget.beforeTools(tools(3)));
    }

    @Test void repeatedToolRoundsConsumeTheSameBudget() {
        AiRunBudget budget = new AiRunBudget(Duration.ofMinutes(1), 2);
        budget.beforeTools(tools(1));
        budget.beforeTools(tools(1));
        assertThrows(IllegalStateException.class, () -> budget.beforeTools(tools(1)));
    }

    @Test void expiredOrCancelledTasksCannotStartMoreModelCalls() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        AiRunBudget expired = new AiRunBudget(Duration.ZERO, 4);
        assertThrows(IllegalStateException.class, () -> BoundedChatModels.blocking(model, expired)
                .generate(List.of(UserMessage.from("hello"))));
        AiRunBudget cancelled = new AiRunBudget(Duration.ofMinutes(1), 4);
        cancelled.close();
        assertThrows(IllegalStateException.class, () -> BoundedChatModels.blocking(model, cancelled)
                .generate(List.of(UserMessage.from("hello"))));
        verifyNoInteractions(model);
    }

    @Test void blockingModelRejectsOversizedToolResponseBeforeExecution() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyList(), anyList())).thenReturn(tools(5));
        ChatLanguageModel bounded = BoundedChatModels.blocking(model, new AiRunBudget(Duration.ofMinutes(1), 4));
        assertThrows(IllegalStateException.class, () -> bounded.generate(List.of(UserMessage.from("hello")), List.<ToolSpecification>of()));
    }

    @Test @SuppressWarnings("unchecked") void streamingModelRejectsToolsBeforePassingThemToAiServices() {
        StreamingChatLanguageModel model = mock(StreamingChatLanguageModel.class);
        doAnswer(invocation -> {
            StreamingResponseHandler<AiMessage> callback = invocation.getArgument(2);
            callback.onComplete(tools(5));
            return null;
        }).when(model).generate(anyList(), anyList(), any());
        StreamingResponseHandler<AiMessage> handler = mock(StreamingResponseHandler.class);
        BoundedChatModels.streaming(model, new AiRunBudget(Duration.ofMinutes(1), 4))
                .generate(List.of(UserMessage.from("hello")), List.<ToolSpecification>of(), handler);
        verify(handler).onError(any(IllegalStateException.class));
        verify(handler, never()).onComplete(any());
    }
}
