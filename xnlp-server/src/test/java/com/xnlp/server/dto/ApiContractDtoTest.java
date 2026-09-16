package com.xnlp.server.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiContractDtoTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void datasetRequest_validatesNestedEntriesAndConvertsToDomainModel() {
        DatasetRequest request = new DatasetRequest(
                "sentiment", "demo", com.xnlp.core.eval.NLPTaskType.SENTIMENT_ANALYSIS,
                List.of(new DatasetEntryRequest(null, "great", "positive", Map.of("source", "test"), null)));

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.toModel().getEntries()).singleElement()
                .satisfies(entry -> assertThat(entry.getInput()).isEqualTo("great"));
    }

    @Test
    void nlpRequests_reportMissingAndOutOfRangeValues() {
        NlpClassificationRequest request = new NlpClassificationRequest(
                null, "", List.of());

        assertThat(validator.validate(request)).extracting(v -> v.getPropertyPath().toString())
                .contains("text", "categories");

        NlpAnalyzeRequest analyze = new NlpAnalyzeRequest(
                "TOK", null, "hello", null, null, null, 0, null, null, null, null);
        assertThat(validator.validate(analyze)).extracting(v -> v.getPropertyPath().toString())
                .containsExactly("topK");
    }

    @Test
    void ragRequests_applySafeDefaultsAndValidateCrossFieldLimits() {
        KnowledgeSearchRequest search = new KnowledgeSearchRequest(
                "how to deploy", 5, 0.25, Map.of("language", "en"), true, null);
        RagRequest rag = new RagRequest("summarize", 3, null, null, null, null);

        assertThat(validator.validate(search)).isEmpty();
        assertThat(search.rerankTopN()).isEqualTo(5);
        assertThat(validator.validate(rag)).isEmpty();
        assertThat(rag.maxContextChunks()).isEqualTo(3);
        assertThat(rag.insufficientContextPolicy())
                .isEqualTo(RagRequest.InsufficientContextPolicy.REJECT);
        assertThat(rag.timeoutMs()).isNull();

        RagRequest lowConfidence = new RagRequest(
                "summarize", 3, null, 2, "conversation", null,
                RagRequest.InsufficientContextPolicy.ANSWER_WITH_LOW_CONFIDENCE, 5_000L);
        assertThat(validator.validate(lowConfidence)).isEmpty();
        assertThat(lowConfidence.timeoutMs()).isEqualTo(5_000L);

        KnowledgeSearchRequest invalid = new KnowledgeSearchRequest(
                "query", 2, null, null, true, 3);
        assertThat(validator.validate(invalid)).extracting(v -> v.getPropertyPath().toString())
                .contains("rerankTopNValid");

        RagRequest invalidRag = new RagRequest(
                "query", 2, null, 3, null, null, null, 99L);
        assertThat(validator.validate(invalidRag)).extracting(v -> v.getPropertyPath().toString())
                .contains("contextLimitValid", "timeoutMs");
    }

    @Test
    void knowledgeDocumentAndChunkPolicy_rejectUnsafeInput() {
        KnowledgeDocumentCreateRequest document = new KnowledgeDocumentCreateRequest(
                "", "", null, null, null, null);
        ChunkPolicyRequest chunkPolicy = new ChunkPolicyRequest(500, 500, null);
        KnowledgeDocumentUpdateRequest update = new KnowledgeDocumentUpdateRequest(
                null, null, null, 0L);

        assertThat(validator.validate(document)).extracting(v -> v.getPropertyPath().toString())
                .contains("title", "content", "sourceType");
        assertThat(validator.validate(chunkPolicy)).extracting(v -> v.getPropertyPath().toString())
                .contains("overlapValid");
        assertThat(validator.validate(update)).extracting(v -> v.getPropertyPath().toString())
                .contains("expectedVersion", "updatePresent");
    }

    @Test
    void pageResponse_exposesNavigationMetadataAndEntryCompatibilityAlias() {
        PageResponse<String> response = PageResponse.of(List.of("a"), 0, 1, 3);

        assertThat(response.items()).containsExactly("a");
        assertThat(response.entries()).containsExactly("a");
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.hasNext()).isTrue();
    }
}
