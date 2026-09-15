package com.xnlp.server.service;

import com.xnlp.core.config.ModelConfig;
import com.xnlp.core.config.ModelProtocol;
import com.xnlp.core.config.ModelType;
import com.xnlp.core.model.ModelInfo;
import com.xnlp.server.dto.ModelTestRequest;
import com.xnlp.server.dto.ProviderDiagnosticResponse;
import com.xnlp.server.dto.ProviderDiagnosticResponse.ProviderDiagnostic;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Builds passive and active provider diagnostics without exposing credentials. */
@Service
public class ProviderDiagnosticsService {

    private static final Set<ModelType> PROVIDER_TYPES = EnumSet.of(
            ModelType.CHAT, ModelType.EMBEDDING, ModelType.RERANKING);

    private final ModelCatalogService catalog;
    private final ModelConnectionTestService connectionTests;
    private final ObjectProvider<ChatModel> chatModels;
    private final ObjectProvider<EmbeddingModel> embeddingModels;

    public ProviderDiagnosticsService(ModelCatalogService catalog,
                                      ModelConnectionTestService connectionTests,
                                      ObjectProvider<ChatModel> chatModels,
                                      ObjectProvider<EmbeddingModel> embeddingModels) {
        this.catalog = catalog;
        this.connectionTests = connectionTests;
        this.chatModels = chatModels;
        this.embeddingModels = embeddingModels;
    }

    public ProviderDiagnosticResponse diagnose(boolean activeProbe) {
        List<ProviderDiagnostic> diagnostics = new ArrayList<>();
        Set<ModelType> representedTypes = EnumSet.noneOf(ModelType.class);

        for (ModelInfo info : catalog.list()) {
            if (!PROVIDER_TYPES.contains(info.getType())) continue;
            catalog.getConfig(info.getName()).ifPresent(config -> {
                diagnostics.add(diagnose(config, activeProbe));
                representedTypes.add(config.getType());
            });
        }

        addSpringAiRuntime(diagnostics, representedTypes, ModelType.CHAT,
                chatModels.orderedStream().findFirst().orElse(null));
        addSpringAiRuntime(diagnostics, representedTypes, ModelType.EMBEDDING,
                embeddingModels.orderedStream().findFirst().orElse(null));

        for (ModelType type : PROVIDER_TYPES) {
            if (!representedTypes.contains(type)) diagnostics.add(unconfigured(type));
        }
        return new ProviderDiagnosticResponse(Instant.now(), activeProbe, diagnostics);
    }

    private ProviderDiagnostic diagnose(ModelConfig config, boolean activeProbe) {
        long started = System.nanoTime();
        if (requiresApiKey(config.getProtocol()) && isBlank(config.getApiKey())) {
            return diagnostic(config, false, false, false, "unconfigured", null, started,
                    "missing_api_key", "An API key is required for this provider.",
                    "Configure the provider API key and run the probe again.");
        }
        if (isBlank(config.getModelName())) {
            return diagnostic(config, false, false, false, "unconfigured", null, started,
                    "missing_model", "A provider model identifier is required.",
                    "Configure model_name and run the probe again.");
        }
        if (isSpringAi(config.getProtocol())) {
            boolean available = config.getType() == ModelType.CHAT
                    ? chatModels.orderedStream().findFirst().isPresent()
                    : embeddingModels.orderedStream().findFirst().isPresent();
            return diagnostic(config, true, available, available,
                    available ? "usable" : "configured", null, started,
                    available ? null : "runtime_unavailable",
                    available ? "Spring AI runtime bean is available."
                            : "The profile exists, but no matching Spring AI runtime bean is available.",
                    available ? null : "Check Spring AI provider properties and application startup logs.");
        }
        if (!activeProbe) {
            return diagnostic(config, true, null, null, "configured", null, started,
                    null, "Provider profile is configured; reachability has not been probed.",
                    "Run the active provider probe to verify connectivity and model access.");
        }

        Map<String, Object> result = connectionTests.test(config, new ModelTestRequest());
        boolean succeeded = "succeeded".equals(result.get("status"));
        Integer httpStatus = result.get("httpStatus") instanceof Number number ? number.intValue() : null;
        String message = sanitize(config, stringValue(result.get("message")));
        boolean reachable = succeeded || httpStatus != null;
        String failureCode = succeeded ? null : failureCode(httpStatus, message);
        return diagnostic(config, true, reachable, succeeded, succeeded ? "usable" : "unavailable",
                httpStatus, started, failureCode,
                succeeded ? "Provider accepted a test request." : defaultFailureMessage(message),
                succeeded ? null : suggestedAction(failureCode));
    }

    private void addSpringAiRuntime(List<ProviderDiagnostic> diagnostics, Set<ModelType> representedTypes,
                                    ModelType type, Object runtime) {
        if (runtime == null || representedTypes.contains(type)) return;
        String className = runtime.getClass().getSimpleName();
        String suffix = type == ModelType.CHAT ? "ChatModel" : "EmbeddingModel";
        String provider = className.endsWith(suffix)
                ? className.substring(0, className.length() - suffix.length()).toLowerCase(Locale.ROOT)
                : className.toLowerCase(Locale.ROOT);
        ModelProtocol protocol = type == ModelType.CHAT
                ? ModelProtocol.SPRING_AI_CHAT : ModelProtocol.SPRING_AI_EMBEDDING;
        diagnostics.add(new ProviderDiagnostic(
                "spring-ai-" + type.name().toLowerCase(Locale.ROOT), type, protocol, provider,
                "configured", null, true, true, true, "usable", null, 0,
                null, "Spring AI runtime bean is available.", null));
        representedTypes.add(type);
    }

    private ProviderDiagnostic unconfigured(ModelType type) {
        String capability = type.name().toLowerCase(Locale.ROOT);
        return new ProviderDiagnostic(capability, type, null, null, null, null,
                false, false, false, "unconfigured", null, 0,
                "provider_unconfigured", "No " + capability + " provider is configured.",
                "Create a " + capability + " model profile or enable a matching Spring AI provider.");
    }

    private ProviderDiagnostic diagnostic(ModelConfig config, boolean configured,
                                          Boolean reachable, Boolean usable, String status,
                                          Integer httpStatus, long started, String failureCode,
                                          String message, String suggestedAction) {
        return new ProviderDiagnostic(
                config.getName(), config.getType(), config.getProtocol(), config.getProvider(),
                config.getModelName(), redactEndpoint(config.getBaseUrl()), configured,
                reachable, usable, status, httpStatus,
                (System.nanoTime() - started) / 1_000_000,
                failureCode, message, suggestedAction);
    }

    private static boolean isSpringAi(ModelProtocol protocol) {
        return protocol == ModelProtocol.SPRING_AI_CHAT || protocol == ModelProtocol.SPRING_AI_EMBEDDING;
    }

    private static boolean requiresApiKey(ModelProtocol protocol) {
        return protocol != ModelProtocol.OLLAMA_CHAT
                && protocol != ModelProtocol.OLLAMA_EMBEDDINGS
                && !isSpringAi(protocol);
    }

    private static String failureCode(Integer httpStatus, String message) {
        if (httpStatus != null) {
            if (httpStatus == 401 || httpStatus == 403) return "authentication_failed";
            if (httpStatus == 404) return "model_or_endpoint_not_found";
            if (httpStatus == 429) return "rate_limited";
            if (httpStatus >= 500) return "provider_unavailable";
            return "provider_rejected_request";
        }
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (normalized.contains("timed out") || normalized.contains("timeout")) return "connection_timeout";
        return "connection_failed";
    }

    private static String suggestedAction(String failureCode) {
        return switch (failureCode) {
            case "authentication_failed" -> "Verify the API key and provider account permissions.";
            case "model_or_endpoint_not_found" -> "Verify base_url, protocol and model_name.";
            case "rate_limited" -> "Reduce request frequency or review provider quota.";
            case "provider_unavailable" -> "Retry later and inspect the provider service status.";
            case "connection_timeout", "connection_failed" -> "Verify network access, DNS and base_url.";
            default -> "Review the provider response and model profile configuration.";
        };
    }

    private static String redactEndpoint(String value) {
        if (isBlank(value)) return null;
        try {
            URI uri = URI.create(value);
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
        } catch (Exception ignored) {
            int query = value.indexOf('?');
            return query >= 0 ? value.substring(0, query) : value;
        }
    }

    private static String sanitize(ModelConfig config, String value) {
        if (value == null) return null;
        String sanitized = value.replaceAll("(?i)(key=)[^&\\s]+", "$1***");
        if (!isBlank(config.getApiKey())) sanitized = sanitized.replace(config.getApiKey(), "***");
        return sanitized;
    }

    private static String defaultFailureMessage(String message) {
        return isBlank(message) ? "The provider probe failed." : message;
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
