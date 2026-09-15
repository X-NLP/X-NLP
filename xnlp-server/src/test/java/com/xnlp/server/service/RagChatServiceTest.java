package com.xnlp.server.service;

import com.xnlp.core.rag.RagContractException;
import com.xnlp.core.rag.RagErrorCode;
import com.xnlp.core.rag.RetrievalMatch;
import com.xnlp.core.rag.RetrievalResult;
import com.xnlp.core.registry.ModelRegistry;
import com.xnlp.server.config.RagChatProperties;
import com.xnlp.server.dto.RagRequest;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagChatServiceTest {

    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void shutDownExecutorAndClearInterrupt() {
        executors.forEach(ExecutorService::shutdownNow);
        Thread.interrupted();
    }

    @Test
    void chat_buildsGroundedPromptPropagatesConversationAndMapsUsageAndCitation() {
        RetrievalService retrieval = retrievalWith(List.of(match("doc-1", "chunk-1", "portable database guide")));
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model("chat-v1")
                .usage(new DefaultUsage(12, 5, 17))
                .build();
        CapturingChatModel model = new CapturingChatModel(prompt -> new ChatResponse(
                List.of(new Generation(new AssistantMessage("Use the guide [S1]."))), metadata));
        RagChatService service = service(retrieval, providerOf(model), defaults());

        var answer = service.chat("kb-1", new RagRequest(
                "How do I switch databases?", 4, 0.2, 2,
                "conversation-7", "Be concise.", null, 5_000L));

        assertThat(answer.answer()).isEqualTo("Use the guide [S1].");
        assertThat(answer.lowConfidence()).isFalse();
        assertThat(answer.model()).isEqualTo("chat-v1");
        assertThat(answer.usage()).containsEntry("promptTokens", 12)
                .containsEntry("completionTokens", 5)
                .containsEntry("totalTokens", 17);
        assertThat(answer.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.documentId()).isEqualTo("doc-1");
            assertThat(citation.chunkId()).isEqualTo("chunk-1");
        });

        assertThat(model.calls()).isEqualTo(1);
        Prompt prompt = model.lastPrompt();
        assertThat(prompt.getSystemMessage().getText())
                .contains("Be concise.", "Mandatory grounding and citation rules");
        assertThat(prompt.getUserMessage().getText())
                .contains("How do I switch databases?", "portable database guide", "S1");
        assertThat(prompt.getUserMessage().getMetadata())
                .containsEntry("conversationId", "conversation-7");
    }

    @Test
    void chat_emptyContextRejectsOrExplicitlyMarksLowConfidence() {
        RetrievalService retrieval = retrievalWith(List.of());
        CapturingChatModel model = new CapturingChatModel(prompt -> response("This is not grounded."));
        RagChatService service = service(retrieval, providerOf(model), defaults());

        assertThatThrownBy(() -> service.chat("kb-1", new RagRequest(
                "question", 3, null, 2, null, null,
                RagRequest.InsufficientContextPolicy.REJECT, null)))
                .isInstanceOfSatisfying(RagContractException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(RagErrorCode.INSUFFICIENT_CONTEXT);
                    assertThat(error.getMessage()).isEqualTo("No sufficient knowledge context was found");
                });
        assertThat(model.calls()).isZero();

        var answer = service.chat("kb-1", new RagRequest(
                "question", 3, null, 2, null, null,
                RagRequest.InsufficientContextPolicy.ANSWER_WITH_LOW_CONFIDENCE, null));

        assertThat(answer.lowConfidence()).isTrue();
        assertThat(answer.citations()).isEmpty();
        assertThat(answer.answer()).isEqualTo("This is not grounded.");
    }

    @Test
    void chat_contextBudgetExhaustionUsesInsufficientContextPolicy() {
        RetrievalMatch oversizedSource = new RetrievalMatch(
                "doc-1", "chunk-1", "T".repeat(500), "context", null,
                0.9, null, Map.of());
        RetrievalService retrieval = retrievalWith(List.of(oversizedSource));
        CapturingChatModel model = new CapturingChatModel(prompt -> response("This is not grounded."));
        RagChatProperties properties = defaults();
        properties.setMaxContextCharacters(256);
        RagChatService service = service(retrieval, providerOf(model), properties);

        assertThatThrownBy(() -> service.chat("kb-1", new RagRequest(
                "question", 1, null, 1, null, null,
                RagRequest.InsufficientContextPolicy.REJECT, null)))
                .isInstanceOfSatisfying(RagContractException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(RagErrorCode.INSUFFICIENT_CONTEXT));
        assertThat(model.calls()).isZero();

        var answer = service.chat("kb-1", new RagRequest(
                "question", 1, null, 1, null, null,
                RagRequest.InsufficientContextPolicy.ANSWER_WITH_LOW_CONFIDENCE, null));

        assertThat(answer.lowConfidence()).isTrue();
        assertThat(answer.citations()).isEmpty();
        assertThat(model.lastPrompt().getUserMessage().getText()).contains("<context />");
    }

    @Test
    void chat_timeoutCancelsProviderAndReturnsStableTimeoutError() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        ChatModel model = prompt -> {
            started.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException cancelled) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return response("late response");
        };
        RagChatProperties properties = defaults();
        properties.setMaxTimeout(Duration.ofMillis(100));
        properties.setDefaultTimeout(Duration.ofMillis(100));
        RagChatService service = service(retrievalWith(List.of(match("doc-1", "chunk-1", "context"))),
                providerOf(model), properties);

        assertThatThrownBy(() -> service.chat("kb-1", new RagRequest(
                "question", 1, null, 1, null, null, null, 500L)))
                .isInstanceOfSatisfying(RagContractException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(RagErrorCode.MODEL_TIMEOUT));

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void chat_providerFailureAndMissingModelNeverExposeProviderSecrets() {
        ChatModel failing = prompt -> {
            throw new IllegalStateException("Authorization Bearer sk-secret database.password=hunter2");
        };
        RagChatService failingService = service(
                retrievalWith(List.of(match("doc-1", "chunk-1", "context"))),
                providerOf(failing), defaults());

        assertThatThrownBy(() -> failingService.chat("kb-1", request()))
                .isInstanceOfSatisfying(RagContractException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(RagErrorCode.PROVIDER_UNCONFIGURED);
                    assertThat(error.getMessage()).isEqualTo("Chat provider request failed");
                    assertThat(error.toString()).doesNotContain("sk-secret", "hunter2", "Authorization");
                });

        RagChatService missingService = service(
                retrievalWith(List.of(match("doc-1", "chunk-1", "context"))),
                providerOf(), defaults());
        assertThatThrownBy(() -> missingService.chat("kb-1", request()))
                .isInstanceOfSatisfying(RagContractException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(RagErrorCode.PROVIDER_UNCONFIGURED);
                    assertThat(error.getMessage()).isEqualTo("No Spring AI ChatModel is configured");
                });
    }

    @Test
    void chat_interruptedWaitRestoresCallerInterruptStatus() {
        CountDownLatch release = new CountDownLatch(1);
        ChatModel model = prompt -> {
            try {
                release.await();
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
            }
            return response("cancelled");
        };
        RagChatService service = service(
                retrievalWith(List.of(match("doc-1", "chunk-1", "context"))),
                providerOf(model), defaults());
        Thread.currentThread().interrupt();

        assertThatThrownBy(() -> service.chat("kb-1", request()))
                .isInstanceOfSatisfying(RagContractException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(RagErrorCode.MODEL_TIMEOUT));
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        release.countDown();
    }

    private RagChatService service(
            RetrievalService retrieval,
            ObjectProvider<ChatModel> models,
            RagChatProperties properties) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executors.add(executor);
        ModelRegistry registry = mock(ModelRegistry.class);
        when(registry.listModels()).thenReturn(List.of());
        return new RagChatService(
                models, emptyTracerProvider(), registry, retrieval,
                new RagContextAssembler(), new RagCitationMapper(properties),
                properties, executor);
    }

    private static RetrievalService retrievalWith(List<RetrievalMatch> matches) {
        RetrievalService retrieval = mock(RetrievalService.class);
        when(retrieval.search(any(), any(), any(Integer.class), any(), any(), any(Boolean.class), any(Integer.class)))
                .thenReturn(new RetrievalResult("question", matches, "embed-v1", null, 4, "trace-1"));
        return retrieval;
    }

    private static RagRequest request() {
        return new RagRequest("question", 1, null, 1, null, null);
    }

    private static RagChatProperties defaults() {
        return new RagChatProperties();
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static RetrievalMatch match(String documentId, String chunkId, String content) {
        return new RetrievalMatch(
                documentId, chunkId, "Title " + documentId, content, null,
                0.9, null, Map.of());
    }

    @SafeVarargs
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T... values) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(ignored -> Stream.of(values));
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<Tracer> emptyTracerProvider() {
        ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
    private static final class CapturingChatModel implements ChatModel {
        private final Function<Prompt, ChatResponse> responder;
        private final AtomicReference<Prompt> lastPrompt = new AtomicReference<>();
        private final AtomicInteger calls = new AtomicInteger();

        private CapturingChatModel(Function<Prompt, ChatResponse> responder) {
            this.responder = responder;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            lastPrompt.set(prompt);
            calls.incrementAndGet();
            return responder.apply(prompt);
        }

        private Prompt lastPrompt() {
            return lastPrompt.get();
        }

        private int calls() {
            return calls.get();
        }
    }

}
