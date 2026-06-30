package com.xnlp.core.errors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("XNLPException hierarchy")
class XNLPExceptionTest {

    @Test
    @DisplayName("should carry code and detail")
    void exceptionProperties() {
        XNLPException ex = new XNLPException("msg", Map.of("k", "v"));

        assertThat(ex.getMessage()).isEqualTo("msg");
        assertThat(ex.getDetail()).containsEntry("k", "v");
    }

    @Test
    @DisplayName("ModelNotFoundError should have status 404")
    void modelNotFound() {
        ModelNotFoundError ex = new ModelNotFoundError("model missing");
        assertThat(ex.getMessage()).isEqualTo("model missing");
        assertThat(ex).isInstanceOf(XNLPException.class);
    }

    @Test
    @DisplayName("PredictionError should carry cause")
    void predictionError() {
        RuntimeException cause = new RuntimeException("boom");
        PredictionError ex = new PredictionError("prediction failed", cause);

        assertThat(ex.getMessage()).isEqualTo("prediction failed");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("ConfigException should be XNLPException")
    void configException() {
        ConfigException ex = new ConfigException("bad config");
        assertThat(ex).isInstanceOf(XNLPException.class);
    }
}
