package com.xnlp.server.service;

import com.xnlp.core.config.ModelConfig;
import com.xnlp.core.config.ModelProtocol;
import com.xnlp.core.config.ModelType;
import com.xnlp.core.model.ModelInfo;
import com.xnlp.server.dto.ProviderDiagnosticResponse;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ProviderDiagnosticsServiceTest {

    @Test
    void passiveDiagnosis_doesNotMakeNetworkCalls_andRedactsEndpoint() {
        ModelConfig config = openAiChat("prod-chat", "super-secret");
        ModelCatalogService catalog = catalogWith(config);
        ModelConnectionTestService connection = mock(ModelConnectionTestService.class);

        ProviderDiagnosticsService service = new ProviderDiagnosticsService(
                catalog, connection, emptyProvider(), emptyProvider());

        ProviderDiagnosticResponse response = service.diagnose(false);
        ProviderDiagnosticResponse.ProviderDiagnostic diagnostic = diagnosticFor(response, "prod-chat");

        assertThat(diagnostic.configured()).isTrue();
        assertThat(diagnostic.reachable()).isNull();
        assertThat(diagnostic.usable()).isNull();
        assertThat(diagnostic.status()).isEqualTo("configured");
        assertThat(diagnostic.endpoint()).isEqualTo("https://api.example.test/v1");
        verifyNoInteractions(connection);
    }

    @Test
    void missingApiKey_isUnconfigured_andDoesNotProbe() {
        ModelConfig config = openAiChat("missing-key", null);
        ModelCatalogService catalog = catalogWith(config);
        ModelConnectionTestService connection = mock(ModelConnectionTestService.class);

        ProviderDiagnosticsService service = new ProviderDiagnosticsService(
                catalog, connection, emptyProvider(), emptyProvider());

        ProviderDiagnosticResponse.ProviderDiagnostic diagnostic =
                diagnosticFor(service.diagnose(true), "missing-key");

        assertThat(diagnostic.configured()).isFalse();
        assertThat(diagnostic.reachable()).isFalse();
        assertThat(diagnostic.usable()).isFalse();
        assertThat(diagnostic.failureCode()).isEqualTo("missing_api_key");
        verifyNoInteractions(connection);
    }

    @Test
    void activeDiagnosis_classifiesAuthenticationFailure_withoutLeakingSecret() {
        ModelConfig config = openAiChat("auth-failure", "super-secret");
        ModelCatalogService catalog = catalogWith(config);
        ModelConnectionTestService connection = mock(ModelConnectionTestService.class);
        when(connection.test(eq(config), any())).thenReturn(Map.of(
                "status", "failed",
                "httpStatus", 401,
                "message", "invalid key super-secret"));

        ProviderDiagnosticsService service = new ProviderDiagnosticsService(
                catalog, connection, emptyProvider(), emptyProvider());

        ProviderDiagnosticResponse.ProviderDiagnostic diagnostic =
                diagnosticFor(service.diagnose(true), "auth-failure");

        assertThat(diagnostic.configured()).isTrue();
        assertThat(diagnostic.reachable()).isTrue();
        assertThat(diagnostic.usable()).isFalse();
        assertThat(diagnostic.status()).isEqualTo("unavailable");
        assertThat(diagnostic.httpStatus()).isEqualTo(401);
        assertThat(diagnostic.failureCode()).isEqualTo("authentication_failed");
        assertThat(diagnostic.message()).doesNotContain("super-secret");
        verify(connection).test(eq(config), any());
    }

    @Test
    void noConfiguredProfile_reportsRerankingAsUnconfigured() {
        ProviderDiagnosticsService service = new ProviderDiagnosticsService(
                catalogWith(), mock(ModelConnectionTestService.class), emptyProvider(), emptyProvider());

        ProviderDiagnosticResponse response = service.diagnose(false);

        ProviderDiagnosticResponse.ProviderDiagnostic reranking = response.diagnostics().stream()
                .filter(item -> item.type() == ModelType.RERANKING)
                .findFirst().orElseThrow();
        assertThat(reranking.status()).isEqualTo("unconfigured");
        assertThat(reranking.failureCode()).isEqualTo("provider_unconfigured");
        assertThat(response.activeProbe()).isFalse();
    }

    @Test
    void springAiRuntimeBean_isReportedAsUsable() {
        ObjectProvider<ChatModel> chats = providerOf(mock(ChatModel.class));
        ProviderDiagnosticsService service = new ProviderDiagnosticsService(
                catalogWith(), mock(ModelConnectionTestService.class), chats, emptyProvider());

        ProviderDiagnosticResponse.ProviderDiagnostic diagnostic = service.diagnose(false).diagnostics().stream()
                .filter(item -> item.type() == ModelType.CHAT)
                .findFirst().orElseThrow();

        assertThat(diagnostic.protocol()).isEqualTo(ModelProtocol.SPRING_AI_CHAT);
        assertThat(diagnostic.configured()).isTrue();
        assertThat(diagnostic.reachable()).isTrue();
        assertThat(diagnostic.usable()).isTrue();
        assertThat(diagnostic.status()).isEqualTo("usable");
    }

    private static ModelConfig openAiChat(String name, String apiKey) {
        ModelConfig config = new ModelConfig();
        config.setName(name);
        config.setType(ModelType.CHAT);
        config.setProtocol(ModelProtocol.OPENAI_CHAT_COMPLETIONS);
        config.setProvider("openai");
        config.setModelName("gpt-test");
        config.setBaseUrl("https://api.example.test/v1?token=do-not-return");
        config.setApiKey(apiKey);
        return config;
    }

    private static ModelCatalogService catalogWith(ModelConfig... configs) {
        ModelCatalogService catalog = mock(ModelCatalogService.class);
        List<ModelInfo> infos = Stream.of(configs).map(ModelInfo::fromConfig).toList();
        when(catalog.list()).thenReturn(infos);
        for (ModelConfig config : configs) {
            when(catalog.getConfig(config.getName())).thenReturn(java.util.Optional.of(config));
        }
        return catalog;
    }

    private static ProviderDiagnosticResponse.ProviderDiagnostic diagnosticFor(
            ProviderDiagnosticResponse response, String name) {
        return response.diagnostics().stream()
                .filter(item -> name.equals(item.name()))
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.empty());
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.of(value));
        return provider;
    }
}
