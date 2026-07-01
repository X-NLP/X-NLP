package com.xnlp.core.registry;

import com.xnlp.core.config.ModelConfig;
import com.xnlp.core.errors.ModelNotFoundError;
import com.xnlp.core.model.ModelInfo;
import com.xnlp.core.model.PredictRequest;
import com.xnlp.core.model.PredictResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ModelRegistry")
class ModelRegistryTest {

    private ModelRegistry registry;
    private ChatModel stubChatModel;

    @BeforeEach
    void setUp() {
        registry = new ModelRegistry();
        stubChatModel = new StubChatModel();
    }

    @Test
    @DisplayName("should load model and list it")
    void loadAndList() {
        ModelConfig cfg = cfg("test-model");
        registry.registerChatModel("test-model", stubChatModel, ModelInfo.fromConfig(cfg));

        List<ModelInfo> models = registry.listModels();
        assertThat(models).hasSize(1);
        assertThat(models.get(0).getName()).isEqualTo("test-model");
        assertThat(models.get(0).getStatus()).isEqualTo("loaded");
    }

    @Test
    @DisplayName("should unload model")
    void unloadModel() {
        registry.registerChatModel("test-model", stubChatModel,
                ModelInfo.fromConfig(cfg("test-model")));
        registry.unloadModel("test-model");

        assertThat(registry.listModels()).isEmpty();
    }

    @Test
    @DisplayName("should predict through loaded model")
    void predict() {
        registry.registerChatModel("test-model", stubChatModel,
                ModelInfo.fromConfig(cfg("test-model")));

        PredictRequest req = new PredictRequest();
        req.setText("hello");
        PredictResponse resp = registry.predict(req);

        assertThat(resp.getText()).contains("echo");
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
        registry.registerChatModel("only-model", stubChatModel,
                ModelInfo.fromConfig(cfg("only-model")));

        PredictRequest req = new PredictRequest();
        req.setText("hello");
        PredictResponse resp = registry.predict(req);
        assertThat(resp.getModel()).isEqualTo("only-model");
    }

    @Test
    @DisplayName("should get model info by name")
    void getModel() {
        registry.registerChatModel("foo", stubChatModel,
                ModelInfo.fromConfig(cfg("foo")));

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
    @DisplayName("should shutdown and release all resources")
    void shutdown() {
        registry.registerChatModel("m1", stubChatModel, ModelInfo.fromConfig(cfg("m1")));
        registry.registerChatModel("m2", stubChatModel, ModelInfo.fromConfig(cfg("m2")));
        registry.shutdown();

        assertThat(registry.listModels()).isEmpty();
    }

    @Test
    @DisplayName("should apply pre-processing pipeline")
    void pipelineApplied() {
        registry.registerChatModel("pipe-model", stubChatModel,
                ModelInfo.fromConfig(cfg("pipe-model")));

        PredictRequest req = new PredictRequest();
        req.setText("  hello   world  ");
        PredictResponse resp = registry.predict(req);
        // TextNormalizerPipeline trims and collapses whitespace
        assertThat(resp.getText()).contains("hello world");
    }

    private static ModelConfig cfg(String name) {
        ModelConfig c = new ModelConfig();
        c.setName(name);
        c.setModelPath("models/" + name + ".onnx");
        c.setBackend("test");
        return c;
    }

    /** Stub ChatModel for testing. */
    static class StubChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            String text = prompt.getInstructions().get(0).getText();
            Generation gen = new Generation(new AssistantMessage("echo: " + text));
            return new ChatResponse(List.of(gen));
        }
    }
}
