package com.xnlp.core.pipeline;

import com.xnlp.core.model.PredictRequest;
import com.xnlp.core.model.PredictResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PipelineManager")
class PipelineManagerTest {

    private PipelineManager manager;

    @BeforeEach
    void setUp() {
        manager = new PipelineManager();
    }

    @Test
    @DisplayName("should apply pre-processing in ascending priority order (lower first)")
    void preProcessingOrder() {
        manager.register(new AppendPipeline("first", 10, "[A]"));
        manager.register(new AppendPipeline("second", 20, "[B]"));

        PredictRequest req = new PredictRequest();
        req.setText("hello");
        PredictRequest result = manager.applyPreProcessing("any", req);

        // first(10) prepends [A] -> [A]hello, second(20) prepends [B] -> [B][A]hello
        assertThat(result.getText()).isEqualTo("[B][A]hello");
    }

    @Test
    @DisplayName("should apply post-processing in descending priority order (higher first)")
    void postProcessingOrder() {
        manager.register(new AppendPipeline("first", 10, "]A["));
        manager.register(new AppendPipeline("second", 20, "]B["));

        PredictResponse resp = PredictResponse.ok("hello", "m", 0);
        PredictResponse result = manager.applyPostProcessing("any", resp);

        // reverse: second(20) prepends ]B[ -> ]B[hello, first(10) prepends ]A[ -> ]A[]B[hello
        assertThat(result.getText()).isEqualTo("]A[]B[hello");
    }

    @Test
    @DisplayName("should skip when pipeline returns null")
    void skipOnNullPre() {
        manager.register(new ProcessingPipeline() {
            @Override public String name() { return "skip-all"; }
            @Override public PredictRequest preProcess(PredictRequest r) { return null; }
        });

        PredictRequest req = new PredictRequest();
        req.setText("hello");
        assertThat(manager.applyPreProcessing("any", req)).isNull();
    }

    @Test
    @DisplayName("should respect supports()")
    void skipUnsupportedModel() {
        manager.register(new AppendPipeline("selective", 10, "[X]") {
            @Override public boolean supports(String modelName) { return false; }
        });

        PredictRequest req = new PredictRequest();
        req.setText("hello");
        PredictRequest result = manager.applyPreProcessing("any", req);
        assertThat(result.getText()).isEqualTo("hello");
    }

    @Test
    @DisplayName("should register and list pipelines")
    void registerAndList() {
        manager.register(new AppendPipeline("p1", 10, "[1]"));
        manager.register(new AppendPipeline("p2", 20, "[2]"));

        assertThat(manager.getPipelines()).hasSize(2);
        assertThat(manager.getPipelines().get(0).name()).isEqualTo("p1");
    }

    static class AppendPipeline implements ProcessingPipeline {
        private final String name;
        private final int priority;
        private final String prefix;

        AppendPipeline(String name, int priority, String prefix) {
            this.name = name;
            this.priority = priority;
            this.prefix = prefix;
        }
        @Override public String name() { return name; }
        @Override public int priority() { return priority; }
        @Override
        public PredictRequest preProcess(PredictRequest request) {
            PredictRequest r = new PredictRequest();
            r.setText(prefix + request.getText());
            r.setModelName(request.getModelName());
            r.setMaxLength(request.getMaxLength());
            return r;
        }
        @Override
        public PredictResponse postProcess(PredictResponse response) {
            return PredictResponse.ok(prefix + response.getText(),
                    response.getModel(), response.getElapsedSeconds());
        }
    }
}
