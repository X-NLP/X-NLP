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
    void pageResponse_exposesNavigationMetadataAndEntryCompatibilityAlias() {
        PageResponse<String> response = PageResponse.of(List.of("a"), 0, 1, 3);

        assertThat(response.items()).containsExactly("a");
        assertThat(response.entries()).containsExactly("a");
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.hasNext()).isTrue();
    }
}
