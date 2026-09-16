package com.xnlp.server.service;

import com.xnlp.core.rag.RagAnswer;
import com.xnlp.core.rag.RagContractException;
import com.xnlp.core.rag.RagErrorCode;
import com.xnlp.core.rag.RetrievalResult;
import com.xnlp.core.registry.ModelRegistry;
import com.xnlp.server.config.RagChatProperties;
import com.xnlp.server.dto.RagRequest;
import io.micrometer.observation.annotation.Observed;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Coordinates retrieval, bounded prompt assembly, chat generation and trusted citation mapping. */
@Service
@Profile("!memory")
public class RagChatService {

    private final ObjectProvider<ChatModel> chatModels;
    private final ObjectProvider<Tracer> tracers;
    private final ModelRegistry registry;
    private final RetrievalService retrievalService;
    private final RagContextAssembler contextAssembler;
    private final RagCitationMapper citationMapper;
    private final RagChatProperties properties;
    private final ExecutorService taskExecutor;

    public RagChatService(
            ObjectProvider<ChatModel> chatModels,
            ObjectProvider<Tracer> tracers,
            ModelRegistry registry,
            RetrievalService retrievalService,
            RagContextAssembler contextAssembler,
            RagCitationMapper citationMapper,
            RagChatProperties properties,
            @Qualifier("ragTaskExecutor") ExecutorService taskExecutor) {
        this.chatModels = chatModels;
        this.tracers = tracers;
        this.registry = registry;
        this.retrievalService = retrievalService;
        this.contextAssembler = contextAssembler;
        this.citationMapper = citationMapper;
        this.properties = properties;
        this.taskExecutor = taskExecutor;
        properties.validate();
    }

    @Observed(name = "xnlp.rag.chat", contextualName = "rag-chat")
    public RagAnswer chat(String knowledgeBaseId, RagRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        long started = System.nanoTime();
        RetrievalResult retrieval = retrievalService.search(
                knowledgeBaseId,
                request.message(),
                request.topK(),
                request.minScore(),
                Map.of(),
                false,
                request.maxContextChunks());

        RagContextAssembler.AssembledContext context = contextAssembler.assemble(
                request.message(),
                request.systemPrompt(),
                retrieval.matches(),
                request.maxContextChunks(),
                properties.getMaxContextCharacters());
        boolean lowConfidence = context.sources().isEmpty();
        if (lowConfidence
                && request.insufficientContextPolicy() == RagRequest.InsufficientContextPolicy.REJECT) {
            throw new RagContractException(
                    RagErrorCode.INSUFFICIENT_CONTEXT,
                    "No sufficient knowledge context was found");
        }

        ChatModel model = resolveChatModel();
        Prompt prompt = prompt(context, request.conversationId());
        ChatResponse response = callModel(model, prompt, timeout(request.timeoutMs()));
        String answer = responseText(response);

        RagCitationMapper.CitationMapping mapped;
        try {
            mapped = citationMapper.map(answer, context.sources());
        } catch (RuntimeException invalidProviderResponse) {
            throw providerFailure("Chat provider returned an invalid response");
        }

        ChatResponseMetadata metadata = response.getMetadata();
        String modelName = metadata == null ? null : metadata.getModel();
        if (modelName == null || modelName.isBlank()) {
            modelName = providerName(model);
        }
        return new RagAnswer(
                mapped.answer(),
                mapped.citations(),
                lowConfidence,
                retrieval,
                modelName,
                providerName(model),
                usage(metadata),
                elapsedMillis(started),
                currentTraceId(retrieval.traceId()));
    }

    private ChatModel resolveChatModel() {
        return chatModels.orderedStream().findFirst()
                .orElseGet(() -> registry.listModels().stream()
                        .map(model -> registry.getChatModel(model.getName()))
                        .filter(java.util.Objects::nonNull)
                        .findFirst()
                        .orElseThrow(() -> new RagContractException(
                                RagErrorCode.PROVIDER_UNCONFIGURED,
                                "No Spring AI ChatModel is configured")));
    }

    private ChatResponse callModel(ChatModel model, Prompt prompt, Duration timeout) {
        Future<ChatResponse> future;
        try {
            future = taskExecutor.submit(() -> ChatClient.create(model)
                    .prompt(prompt)
                    .call()
                    .chatResponse());
        } catch (RejectedExecutionException saturated) {
            throw providerFailure("Chat generation capacity is unavailable");
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeoutFailure) {
            future.cancel(true);
            throw new RagContractException(RagErrorCode.MODEL_TIMEOUT, "Chat model request timed out");
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new RagContractException(RagErrorCode.MODEL_TIMEOUT, "Chat model request was interrupted");
        } catch (ExecutionException providerFailure) {
            throw providerFailure("Chat provider request failed");
        }
    }

    private static Prompt prompt(RagContextAssembler.AssembledContext context, String conversationId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (conversationId != null && !conversationId.isBlank()) {
            metadata.put("conversationId", conversationId.strip());
        }
        UserMessage userMessage = UserMessage.builder()
                .text(context.userPrompt())
                .metadata(metadata)
                .build();
        return new Prompt(
                SystemMessage.builder().text(context.systemPrompt()).build(),
                userMessage);
    }

    private Duration timeout(Long requestedTimeoutMs) {
        Duration requested = requestedTimeoutMs == null
                ? properties.getDefaultTimeout()
                : Duration.ofMillis(requestedTimeoutMs);
        return requested.compareTo(properties.getMaxTimeout()) > 0
                ? properties.getMaxTimeout()
                : requested;
    }

    private static String responseText(ChatResponse response) {
        try {
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                throw providerFailure("Chat provider returned an empty response");
            }
            String text = response.getResult().getOutput().getText();
            if (text == null || text.isBlank()) {
                throw providerFailure("Chat provider returned an empty response");
            }
            return text.strip();
        } catch (RagContractException known) {
            throw known;
        } catch (RuntimeException invalidProviderResponse) {
            throw providerFailure("Chat provider returned an invalid response");
        }
    }

    private static Map<String, Object> usage(ChatResponseMetadata metadata) {
        if (metadata == null || metadata.getUsage() == null) {
            return Map.of();
        }
        Usage usage = metadata.getUsage();
        Map<String, Object> values = new LinkedHashMap<>();
        putIfPresent(values, "promptTokens", usage.getPromptTokens());
        putIfPresent(values, "completionTokens", usage.getCompletionTokens());
        putIfPresent(values, "totalTokens", usage.getTotalTokens());
        putIfPresent(values, "cacheReadInputTokens", usage.getCacheReadInputTokens());
        putIfPresent(values, "cacheWriteInputTokens", usage.getCacheWriteInputTokens());
        return values.isEmpty() ? Map.of() : Map.copyOf(values);
    }

    private static void putIfPresent(Map<String, Object> target, String key, Number value) {
        if (value != null && value.longValue() >= 0) {
            target.put(key, value);
        }
    }

    private String currentTraceId(String retrievalTraceId) {
        Tracer tracer = tracers.getIfAvailable();
        Span span = tracer == null ? null : tracer.currentSpan();
        return span == null ? retrievalTraceId : span.context().traceId();
    }

    private static long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private static RagContractException providerFailure(String message) {
        return new RagContractException(RagErrorCode.PROVIDER_UNCONFIGURED, message);
    }

    private static String providerName(ChatModel model) {
        String name = model.getClass().getSimpleName();
        if (name == null || name.isBlank()) {
            return "ChatModel";
        }
        String provider = name.endsWith("ChatModel")
                ? name.substring(0, name.length() - "ChatModel".length())
                : name;
        return provider.isBlank() ? "ChatModel" : provider;
    }
}
