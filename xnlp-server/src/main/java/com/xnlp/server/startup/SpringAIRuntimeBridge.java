package com.xnlp.server.startup;

import com.xnlp.core.config.ModelProtocol;
import com.xnlp.core.config.ModelSource;
import com.xnlp.core.config.ModelType;
import com.xnlp.core.model.ModelInfo;
import com.xnlp.core.registry.ModelRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Registers the provider-neutral Spring AI runtime in X-NLP's model registry. */
@Component
@Order(0)
public class SpringAIRuntimeBridge implements ApplicationListener<ApplicationReadyEvent> {

    private final ModelRegistry registry;
    private final ObjectProvider<ChatModel> chatModels;

    public SpringAIRuntimeBridge(ModelRegistry registry, ObjectProvider<ChatModel> chatModels) {
        this.registry = registry;
        this.chatModels = chatModels;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        chatModels.orderedStream().findFirst().ifPresent(model -> {
            ModelInfo info = new ModelInfo();
            info.setName("spring-ai-default");
            info.setType(ModelType.CHAT);
            info.setProtocol(ModelProtocol.SPRING_AI_CHAT);
            info.setSource(ModelSource.CUSTOM);
            info.setProvider(providerName(model));
            info.setModelName("configured");
            info.setBackend("spring-ai");
            registry.registerChatModel("spring-ai-default", model, info);
        });
    }

    private String providerName(ChatModel model) {
        String name = model.getClass().getSimpleName();
        return name.endsWith("ChatModel") ? name.substring(0, name.length() - "ChatModel".length()).toLowerCase() : name;
    }
}
