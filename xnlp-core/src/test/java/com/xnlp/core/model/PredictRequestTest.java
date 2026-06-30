package com.xnlp.core.model;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PredictRequest")
class PredictRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    @DisplayName("should serialize/deserialize via Jackson")
    void jacksonRoundTrip() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        PredictRequest req = new PredictRequest();
        req.setText("hello");
        req.setModelName("m1");
        req.setMaxLength(50);

        String json = mapper.writeValueAsString(req);
        PredictRequest parsed = mapper.readValue(json, PredictRequest.class);

        assertThat(parsed.getText()).isEqualTo("hello");
        assertThat(parsed.getModelName()).isEqualTo("m1");
        assertThat(parsed.getMaxLength()).isEqualTo(50);
    }

    @Test
    @DisplayName("should validate blank text")
    void blankTextInvalid() {
        PredictRequest req = new PredictRequest();
        req.setText("");

        Set<ConstraintViolation<PredictRequest>> v = validator.validate(req);
        assertThat(v).isNotEmpty();
        assertThat(v.iterator().next().getMessage()).contains("must not be blank");
    }

    @Test
    @DisplayName("should validate text length")
    void textTooLong() {
        PredictRequest req = new PredictRequest();
        req.setText("x".repeat(33000));

        Set<ConstraintViolation<PredictRequest>> v = validator.validate(req);
        assertThat(v).isNotEmpty();
        assertThat(v.iterator().next().getMessage()).contains("exceeds maximum length");
    }

    @Test
    @DisplayName("should pass with valid request")
    void validRequest() {
        PredictRequest req = new PredictRequest();
        req.setText("hello world");

        Set<ConstraintViolation<PredictRequest>> v = validator.validate(req);
        assertThat(v).isEmpty();
    }
}
