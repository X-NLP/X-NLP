package com.xnlp.server.service;

import com.xnlp.core.api.ComponentResult;
import com.xnlp.core.api.NlpContext;
import com.xnlp.server.dto.PipelineExecuteRequest;
import com.xnlp.server.dto.PipelineTraceResponse;
import com.xnlp.server.nlp.CapabilityRegistry;
import io.micrometer.observation.annotation.Observed;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Executes the composable NLP capabilities and records an auditable node trace. */
@Service
public class PipelineTraceService {

    private static final List<String> TEXT_OUTPUT_KEYS = List.of(
            "outputText", "normalizedText", "summary", "translation", "output");

    private final CapabilityRegistry capabilityRegistry;

    public PipelineTraceService(CapabilityRegistry capabilityRegistry) {
        this.capabilityRegistry = capabilityRegistry;
    }

    @Observed(name = "xnlp.pipeline.execute", contextualName = "pipeline-execute")
    public PipelineTraceResponse execute(PipelineExecuteRequest request) {
        Instant startedAt = Instant.now();
        String traceId = UUID.randomUUID().toString();
        String inputText = request.text();
        String currentText = inputText;
        String language = request.language() == null || request.language().isBlank()
                ? "zh" : request.language();
        List<PipelineTraceResponse.NodeTrace> traces = new ArrayList<>();
        boolean failed = false;

        for (PipelineExecuteRequest.NodeRequest node : request.nodes()) {
            if (failed) {
                traces.add(new PipelineTraceResponse.NodeTrace(
                        node.id(), node.capability(), displayName(node), "skipped",
                        currentText, currentText, 0, Map.of(), "Previous node failed"));
                continue;
            }

            String nodeInput = currentText;
            Instant nodeStarted = Instant.now();
            try {
                Map<String, Object> parameters = merge(request.parameters(), node.parameters());
                ComponentResult result = capabilityRegistry.execute(
                        node.capability(),
                        NlpContext.builder()
                                .text(nodeInput)
                                .textPair(request.textPair())
                                .language(language)
                                .params(parameters)
                                .build());
                String nextText = nextText(result.getData(), nodeInput);
                long elapsed = elapsedMs(nodeStarted);
                traces.add(new PipelineTraceResponse.NodeTrace(
                        node.id(), node.capability(), displayName(node), "completed",
                        nodeInput, nextText, elapsed, result.getData(), null));
                currentText = nextText;
            } catch (RuntimeException ex) {
                failed = true;
                long elapsed = elapsedMs(nodeStarted);
                traces.add(new PipelineTraceResponse.NodeTrace(
                        node.id(), node.capability(), displayName(node), "failed",
                        nodeInput, nodeInput, elapsed, Map.of(), safeMessage(ex)));
            }
        }

        Instant completedAt = Instant.now();
        return new PipelineTraceResponse(
                traceId,
                failed ? "failed" : "completed",
                inputText,
                currentText,
                language,
                startedAt,
                completedAt,
                Duration.between(startedAt, completedAt).toMillis(),
                List.copyOf(traces));
    }

    private static Map<String, Object> merge(Map<String, Object> requestParameters,
                                             Map<String, Object> nodeParameters) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (requestParameters != null) merged.putAll(requestParameters);
        if (nodeParameters != null) merged.putAll(nodeParameters);
        return merged;
    }

    private static String nextText(Map<String, Object> result, String fallback) {
        for (String key : TEXT_OUTPUT_KEYS) {
            Object value = result.get(key);
            if (value instanceof String text && !text.isBlank()) return text;
        }
        return fallback;
    }

    private static String displayName(PipelineExecuteRequest.NodeRequest node) {
        return node.name() == null || node.name().isBlank() ? node.capability().toUpperCase(Locale.ROOT) : node.name();
    }

    private static long elapsedMs(Instant started) {
        return Duration.between(started, Instant.now()).toMillis();
    }

    private static String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
