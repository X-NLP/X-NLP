package com.xnlp.server.runtime.onnx;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnnxRuntimePropertiesTest {

    @Test
    void setTimeout_subMillisecondValue_isRejected() {
        OnnxRuntimeProperties properties = new OnnxRuntimeProperties();

        assertThatThrownBy(() -> properties.setTimeout(Duration.ofNanos(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 millisecond");
    }

    @Test
    void validate_invertedThresholds_areRejected() {
        OnnxRuntimeProperties properties = enabledProperties();
        properties.setNegativeThreshold(0.8);
        properties.setPositiveThreshold(0.2);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negativeThreshold");
    }

    @Test
    void setInputName_blankTensorName_isRejected() {
        OnnxRuntimeProperties properties = enabledProperties();

        assertThatThrownBy(() -> properties.setInputName("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputName");
    }

    private static OnnxRuntimeProperties enabledProperties() {
        OnnxRuntimeProperties properties = new OnnxRuntimeProperties();
        properties.setEnabled(true);
        properties.setRuntimeName("onnx-test");
        properties.setModelPath("/models/test.onnx");
        properties.setModelVersion("v1");
        properties.setModelSha256("a".repeat(64));
        return properties;
    }
}
