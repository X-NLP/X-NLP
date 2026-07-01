package com.xnlp.server.config;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;

@TestConfiguration
public class TestChatModelConfig {

    @Bean
    @Primary
    public ChatModel stubChatModel() {
        return new StubChatModel();
    }

    static class StubChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            String text = prompt.getInstructions().get(0).getText();
            return new ChatResponse(List.of(
                    new Generation(new AssistantMessage("stub: " + text))));
        }
    }
}
