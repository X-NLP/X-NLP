package com.xnlp.server.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void missingOptionalValues_useBackwardCompatibleDefaults() {
        BenchmarkRequest request = new BenchmarkRequest(null, null, null);

        assertThat(request.requests()).isEqualTo(100);
        assertThat(request.concurrency()).isEqualTo(4);
        assertThat(request.text()).isEqualTo("The future of natural language processing is bright.");
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void invalidValues_areReportedByField() {
        BenchmarkRequest request = new BenchmarkRequest(0, 257, " ");

        Set<String> fields = validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(fields).containsExactlyInAnyOrder("requests", "concurrency", "text");
    }
}
