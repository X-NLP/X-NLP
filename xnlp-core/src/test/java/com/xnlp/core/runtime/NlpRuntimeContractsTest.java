package com.xnlp.core.runtime;

import com.xnlp.core.api.ComponentResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NlpRuntimeContractsTest {

    private static final String SHA = "a".repeat(64);

    @Test
    void descriptor_normalizesIdentityChecksumAndCapabilities() {
        Set<String> capabilities = new LinkedHashSet<>(Set.of("sentiment"));

        NlpRuntimeDescriptor descriptor = new NlpRuntimeDescriptor(
                " runtime ", " onnx ", " v1 ", SHA.toUpperCase(), capabilities);
        capabilities.add("NER");

        assertThat(descriptor.name()).isEqualTo("runtime");
        assertThat(descriptor.provider()).isEqualTo("onnx");
        assertThat(descriptor.modelVersion()).isEqualTo("v1");
        assertThat(descriptor.modelSha256()).isEqualTo(SHA);
        assertThat(descriptor.capabilities()).containsExactly("SENTIMENT");
        assertThat(descriptor.supports("Sentiment")).isTrue();
        assertThatThrownBy(() -> descriptor.capabilities().add("NER"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void descriptor_rejectsIncompleteOrInvalidIdentity() {
        assertThatThrownBy(() -> new NlpRuntimeDescriptor(
                "", "onnx", "v1", SHA, Set.of("SENTIMENT")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NlpRuntimeDescriptor(
                "runtime", "onnx", "v1", "bad", Set.of("SENTIMENT")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NlpRuntimeDescriptor(
                "runtime", "onnx", "v1", SHA, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void result_defensivelyCopiesMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mode", "onnx");

        NlpRuntimeResult result = new NlpRuntimeResult(
                ComponentResult.of("SENTIMENT", "label", "positive"), metadata);
        metadata.put("provider", "changed");

        assertThat(result.metadata()).containsOnlyKeys("mode");
        assertThatThrownBy(() -> result.metadata().put("provider", "onnx"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void status_requiresStateAndTimestampAndNormalizesMessage() {
        Instant now = Instant.now();
        NlpRuntimeStatus status = new NlpRuntimeStatus(NlpRuntimeState.READY, " ready ", now);

        assertThat(status.message()).isEqualTo("ready");
        assertThat(status.updatedAt()).isEqualTo(now);
        assertThat(new NlpRuntimeStatus(NlpRuntimeState.NEW, null, now).message()).isEqualTo("new");
    }

    @Test
    void exception_exposesStableCodeAndSafeDetailsWithoutCause() {
        NlpRuntimeException exception = new NlpRuntimeException(
                NlpRuntimeErrorCode.EXECUTION_TIMEOUT,
                "NLP runtime execution timed out",
                Map.of("runtime", "onnx-sentiment"));

        assertThat(exception.getErrorCode()).isEqualTo(NlpRuntimeErrorCode.EXECUTION_TIMEOUT);
        assertThat(exception.getMessage()).doesNotContain("/models/");
        assertThat(exception.getCause()).isNull();
        assertThat(exception.getDetail()).containsEntry("runtimeError", "nlp_runtime_timeout")
                .containsEntry("runtime", "onnx-sentiment");

        NlpRuntimeException reservedDetail = new NlpRuntimeException(
                NlpRuntimeErrorCode.EXECUTION_TIMEOUT,
                "NLP runtime execution timed out",
                Map.of("runtimeError", "overridden"));
        assertThat(reservedDetail.getDetail())
                .containsEntry("runtimeError", "nlp_runtime_timeout");
    }
}
