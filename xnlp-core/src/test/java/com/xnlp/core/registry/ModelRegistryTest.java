package com.xnlp.core.registry;

import com.xnlp.core.config.ModelConfig;
import com.xnlp.core.engine.InferenceEngine;
import com.xnlp.core.errors.ModelNotFoundError;
import com.xnlp.core.model.ModelInfo;
import com.xnlp.core.model.PredictRequest;
import com.xnlp.core.model.PredictResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ModelRegistry")
class ModelRegistryTest {

    private ModelRegistry registry;
    private TestInferenceEngine backend;

    @BeforeEach
    void setUp() {
        registry = new ModelRegistry();
        backend = new TestInferenceEngine("test-backend", true);
        registry.registerBackend(backend);
    }

    @Test
    @DisplayName("should load model and list it")
    void loadAndList() {
        ModelConfig cfg = cfg("test-model", "models/test.onnx");
        ModelInfo info = registry.loadModel(cfg);

        assertThat(info.getName()).isEqualTo("test-model");
        assertThat(info.getStatus()).isEqualTo("loaded");
        assertThat(registry.listModels()).hasSize(1);
        assertThat(backend.isLoaded("test-model")).isTrue();
    }

    @Test
    @DisplayName("should unload model")
    void unloadModel() {
        registry.loadModel(cfg("test-model", "models/test.onnx"));
        registry.unloadModel("test-model");

        assertThat(registry.listModels()).isEmpty();
        assertThat(backend.isLoaded("test-model")).isFalse();
    }

    @Test
    @DisplayName("should predict through loaded model")
    void predict() {
        registry.loadModel(cfg("test-model", "models/test.onnx"));

        PredictRequest req = new PredictRequest();
        req.setText("hello");
        PredictResponse resp = registry.predict(req);

        assertThat(resp.getText()).contains("test-model");
        assertThat(resp.getModel()).isEqualTo("test-model");
    }

    @Test
    @DisplayName("should throw when model not loaded")
    void predictOnMissingModel() {
        PredictRequest req = new PredictRequest();
        req.setText("hello");
        assertThatThrownBy(() -> registry.predict(req))
                .isInstanceOf(ModelNotFoundError.class);
    }

    @Test
    @DisplayName("should resolve model name when single model loaded")
    void autoResolveModelName() {
        registry.loadModel(cfg("only-model", "models/only.onnx"));

        PredictRequest req = new PredictRequest();
        req.setText("hello");
        // modelName not set — should auto-resolve
        PredictResponse resp = registry.predict(req);
        assertThat(resp.getModel()).isEqualTo("only-model");
    }

    @Test
    @DisplayName("should get model info by name")
    void getModel() {
        registry.loadModel(cfg("foo", "models/foo.onnx"));

        ModelInfo info = registry.getModel("foo");
        assertThat(info.getName()).isEqualTo("foo");
        assertThat(info.getStatus()).isEqualTo("loaded");
    }

    @Test
    @DisplayName("should throw when getting unknown model")
    void getMissingModel() {
        assertThatThrownBy(() -> registry.getModel("nope"))
                .isInstanceOf(ModelNotFoundError.class);
    }

    @Test
    @DisplayName("should prefer explicit backend when specified")
    void preferBackend() {
        InferenceEngine djl = new TestInferenceEngine("djl", true);
        registry.registerBackend(djl);

        ModelConfig cfg = cfg("b-model", "models/b.onnx");
        cfg.setBackend("djl");
        registry.loadModel(cfg);
        assertThat(djl.isLoaded("b-model")).isTrue();
        assertThat(backend.isLoaded("b-model")).isFalse();
    }

    @Test
    @DisplayName("should shutdown and release all resources")
    void shutdown() {
        registry.loadModel(cfg("m1", "models/m1.onnx"));
        registry.loadModel(cfg("m2", "models/m2.onnx"));
        registry.shutdown();

        assertThat(registry.listModels()).isEmpty();
        assertThat(backend.isLoaded("m1")).isFalse();
        assertThat(backend.isLoaded("m2")).isFalse();
    }

    @Test
    @DisplayName("should apply pre-processing pipeline")
    void pipelineApplied() {
        registry.loadModel(cfg("pipe-model", "models/pipe.onnx"));

        PredictRequest req = new PredictRequest();
        req.setText("  hello   world  ");
        PredictResponse resp = registry.predict(req);
        // TextNormalizerPipeline trims and collapses whitespace
        assertThat(resp.getText()).contains("hello world");
    }

    private static ModelConfig cfg(String name, String path) {
        ModelConfig c = new ModelConfig();
        c.setName(name);
        c.setModelPath(path);
        return c;
    }

    /** Stub inference engine for testing. */
    static class TestInferenceEngine implements InferenceEngine {
        private final String name;
        private final boolean supportsAll;
        private final java.util.Set<String> loaded = java.util.concurrent.ConcurrentHashMap.newKeySet();

        TestInferenceEngine(String name, boolean supportsAll) {
            this.name = name;
            this.supportsAll = supportsAll;
        }
        @Override public String backendName() { return name; }
        @Override public boolean supports(String path) { return supportsAll; }
        @Override public void load(String modelName, String path, Map<String, Object> opts) { loaded.add(modelName); }
        @Override public void unload(String modelName) { loaded.remove(modelName); }
        @Override public boolean isLoaded(String modelName) { return loaded.contains(modelName); }
        @Override
        public PredictResponse predict(PredictRequest req) {
            return PredictResponse.ok("[" + this.name + ":" + req.getModelName() + "] " + req.getText(),
                    req.getModelName(), 0.001);
        }
        @Override public void close() { loaded.clear(); }
    }
}
