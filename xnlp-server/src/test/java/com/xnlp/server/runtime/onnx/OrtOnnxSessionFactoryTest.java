package com.xnlp.server.runtime.onnx;

import com.xnlp.core.api.NlpContext;
import com.xnlp.core.runtime.NlpRuntimeErrorCode;
import com.xnlp.core.runtime.NlpRuntimeException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrtOnnxSessionFactoryTest {

    private static final String CHECKSUM =
            "c9ea45be3dd00fd43865a739458c74c1f7c7fd325faf8294009f43ae9c0628dd";

    @Test
    void officialFixture_loadsAndExecutesOnCurrentPlatformWithoutNetwork() throws Exception {
        Path fixture = Path.of(getClass().getResource("/models/sigmoid.onnx").toURI());
        OnnxRuntimeProperties properties = properties(fixture);
        OnnxNlpRuntime runtime = new OnnxNlpRuntime(properties);

        runtime.load();
        var result = runtime.execute("SENTIMENT", NlpContext.builder().text("真实运行时测试").build());

        assertThat(result.result().getData()).containsKeys("label", "score");
        assertThat((Double) result.result().get("score")).isBetween(0.0, 1.0);
        assertThat(result.metadata()).containsEntry("provider", "onnxruntime")
                .containsEntry("modelSha256", CHECKSUM);
        runtime.close();
    }

    @Test
    void fixture_withUnknownInputName_isRejectedAsInvalidModel() throws Exception {
        Path fixture = Path.of(getClass().getResource("/models/sigmoid.onnx").toURI());
        OnnxRuntimeProperties properties = properties(fixture);
        properties.setInputName("missing-input");
        OnnxNlpRuntime runtime = new OnnxNlpRuntime(properties);

        assertThatThrownBy(runtime::load)
                .isInstanceOfSatisfying(NlpRuntimeException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(NlpRuntimeErrorCode.MODEL_INVALID);
                    assertThat(error.getMessage()).doesNotContain(fixture.toString());
                });
        runtime.close();
    }

    private static OnnxRuntimeProperties properties(Path fixture) {
        OnnxRuntimeProperties properties = new OnnxRuntimeProperties();
        properties.setEnabled(true);
        properties.setRuntimeName("native-fixture");
        properties.setModelPath(fixture.toString());
        properties.setModelVersion("onnx-v1.22.0-sigmoid");
        properties.setModelSha256(CHECKSUM);
        properties.setTimeout(Duration.ofSeconds(2));
        properties.setMaxConcurrency(1);
        properties.setQueueCapacity(1);
        return properties;
    }
}
