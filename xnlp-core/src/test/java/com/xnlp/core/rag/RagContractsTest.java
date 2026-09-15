package com.xnlp.core.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RagContractsTest {

    @Test
    void chunkPolicy_rejectsOverlapThatConsumesWholeChunk() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChunkPolicy(500, 500, ChunkPolicy.SeparatorMode.FIXED))
                .withMessageContaining("less than maxCharacters");
    }

    @Test
    void vectorContracts_defensivelyCopyMutableArraysAndMetadata() {
        float[] vector = {1.0f, 0.5f};
        var mutableMetadata = new java.util.LinkedHashMap<String, Object>();
        mutableMetadata.put("language", "en");
        VectorRecord record = new VectorRecord(
                "chunk-1", "kb-1", "doc-1", "embed-v1", 2, vector,
                "sha256", mutableMetadata, null);

        vector[0] = 99.0f;
        mutableMetadata.put("language", "zh");
        float[] exposed = record.embedding();
        exposed[1] = 99.0f;

        assertThat(record.embedding()).containsExactly(1.0f, 0.5f);
        assertThat(record.metadata()).containsEntry("language", "en");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new VectorRecord(
                        "chunk-2", "kb-1", "doc-1", "embed-v1", 1,
                        new float[]{Float.POSITIVE_INFINITY}, "sha256", Map.of(), null))
                .withMessageContaining("finite");
    }

    @Test
    void vectorSearch_requiresExplicitTenantBoundedTopKAndFiniteValues() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new VectorSearchRequest(
                        "", "kb-1", "embed-v1", new float[]{1}, 10, null, null))
                .withMessageContaining("tenantId");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new VectorSearchRequest(
                        "tenant-a", "kb-1", "embed-v1", new float[]{1}, 101, null, null))
                .withMessageContaining("topK");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new VectorSearchRequest(
                        "tenant-a", "kb-1", "embed-v1", new float[]{Float.NaN}, 10, null, null))
                .withMessageContaining("finite");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new VectorSearchRequest(
                        "tenant-a", "kb-1", "embed-v1", new float[]{1}, 10, Double.NaN, null))
                .withMessageContaining("minScore");
    }

    @Test
    void ragAnswer_rejectsCitationOutsideRetrievalResult() {
        RetrievalMatch match = new RetrievalMatch(
                "doc-1", "chunk-1", "Guide", "retrieved text", null,
                0.8, null, Map.of());
        RetrievalResult retrieval = new RetrievalResult(
                "question", List.of(match), "embed-v1", null, 12, "trace-1");
        Citation invalid = new Citation("doc-2", "chunk-1", "Other", null, "not retrieved");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RagAnswer(
                        "answer", List.of(invalid), retrieval, "chat-v1", "mock", Map.of(), 20, "trace-1"))
                .withMessageContaining("retrieval result");
    }

    @Test
    void ragErrorCode_keepsTransportIndependentClassification() {
        RagContractException error = new RagContractException(
                RagErrorCode.VECTOR_DIMENSION_MISMATCH,
                "Expected 384 dimensions",
                Map.of("expected", 384, "actual", 768));

        assertThat(error.getErrorCode().code()).isEqualTo("vector_dimension_mismatch");
        assertThat(error.getErrorCode().kind()).isEqualTo(RagErrorCode.Kind.UNPROCESSABLE);
        assertThat(error.getDetail()).containsEntry("actual", 768);
    }
}
