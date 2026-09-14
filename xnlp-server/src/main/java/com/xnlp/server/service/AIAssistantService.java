package com.xnlp.server.service;

import io.micrometer.observation.annotation.Observed;
import com.xnlp.core.registry.ModelRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thin application-facing facade over Spring AI's ChatModel.
 *
 * <p>Provider selection remains Spring Boot configuration driven. This keeps
 * the product layer independent from OpenAI/Ollama-specific SDKs while still
 * allowing prompt templates, metadata, and engineering policies to be added
 * in one place.
 */
@Service
public class AIAssistantService {

    private static final String SYSTEM_PROMPT = "You are X-NLP Copilot, an engineering assistant for NLP workflows. "
            + "Prefer concise, actionable answers. When discussing a pipeline, include inputs, outputs, "
            + "failure modes, and a suggested validation step.";

    private final ObjectProvider<ChatModel> chatModels;
    private final ModelRegistry registry;
    private final SemanticSearchService semanticSearchService;

    public AIAssistantService(ObjectProvider<ChatModel> chatModels, ModelRegistry registry,
                              SemanticSearchService semanticSearchService) {
        this.chatModels = chatModels;
        this.registry = registry;
        this.semanticSearchService = semanticSearchService;
    }

    public Map<String, Object> status() {
        ChatModel model = getChatModel(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", model != null);
        result.put("provider", model == null ? null : providerName(model));
        result.put("embeddingAvailable", semanticSearchService.isAvailable());
        result.put("models", registry.listModels().stream().map(info -> info.getName()).toList());
        result.put("checkedAt", Instant.now().toString());
        return result;
    }

    @Observed(name = "xnlp.ai.chat", contextualName = "ai-chat")
    public Map<String, Object> chat(String message, String context, String modelName) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        ChatModel model = getChatModel(modelName);
        if (model == null) {
            throw new IllegalStateException("No Spring AI ChatModel is configured. Set OPENAI_API_KEY or enable Ollama.");
        }

        String userPrompt = context == null || context.isBlank()
                ? message
                : "Project context:\n" + context + "\n\nUser request:\n" + message;
        ChatResponse response = model.call(new Prompt(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage(userPrompt)));
        String content = response.getResult().getOutput().getText();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content == null ? "" : content);
        result.put("model", modelName == null || modelName.isBlank() ? providerName(model) : modelName);
        result.put("provider", providerName(model));
        result.put("timestamp", Instant.now().toString());
        return result;
    }

    private ChatModel getChatModel(String modelName) {
        if (modelName != null && !modelName.isBlank()) {
            ChatModel selected = registry.getChatModel(modelName);
            if (selected != null) return selected;
        }
        return chatModels.orderedStream().findFirst()
                .orElseGet(() -> registry.listModels().stream()
                        .map(info -> registry.getChatModel(info.getName()))
                        .filter(java.util.Objects::nonNull)
                        .findFirst()
                        .orElse(null));
    }

    private String providerName(ChatModel model) {
        String name = model.getClass().getSimpleName();
        return name.endsWith("ChatModel") ? name.substring(0, name.length() - "ChatModel".length()) : name;
    }
}
