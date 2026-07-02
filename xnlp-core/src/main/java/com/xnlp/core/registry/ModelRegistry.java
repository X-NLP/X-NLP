package com.xnlp.core.registry;

import com.xnlp.core.config.ModelConfig;
import com.xnlp.core.config.ModelType;
import com.xnlp.core.errors.ModelLoadError;
import com.xnlp.core.errors.ModelNotFoundError;
import com.xnlp.core.errors.PredictionError;
import com.xnlp.core.model.ModelInfo;
import com.xnlp.core.model.PredictRequest;
import com.xnlp.core.model.PredictResponse;
import com.xnlp.core.pipeline.PipelineManager;
import com.xnlp.core.pipeline.TextNormalizerPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for model management.
 *
 * <p>Maintains a mapping between model names and their Spring AI
 * {@link ChatModel} instances.  Predictions flow through the configured
 * {@link PipelineManager} for pre/post-processing.
 *
 * <p>ChatModels are registered by the server layer via
 * {@link #registerChatModel(String, ChatModel, ModelInfo)}.
 */
public class ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);

    private final Map<String, ModelInfo> models = new ConcurrentHashMap<>();
    private final Map<String, ChatModel> chatModels = new ConcurrentHashMap<>();
    private final PipelineManager pipelineManager = new PipelineManager();
    private final String activeModel;

    public ModelRegistry() {
        this(null);
    }

    public ModelRegistry(String activeModel) {
        this.activeModel = activeModel;
        this.pipelineManager.register(new TextNormalizerPipeline());
    }

    /** Register a processing pipeline. */
    public void registerPipeline(com.xnlp.core.pipeline.ProcessingPipeline pipeline) {
        pipelineManager.register(pipeline);
    }

    /** Access the pipeline manager for external configuration. */
    public PipelineManager getPipelineManager() {
        return pipelineManager;
    }

    /**
     * Register (or override) a ChatModel under the given model name.
     * Called by the server wiring layer after Spring AI auto-config creates ChatModel beans.
     */
    public ModelInfo registerChatModel(String name, ChatModel chatModel, ModelInfo info) {
        chatModels.put(name, chatModel);
        info.setStatus("loaded");
        info.setLoadedAt(Instant.now());
        models.put(name, info);
        log.info("Registered ChatModel: name={} provider={}", name, info.getProvider());
        return info;
    }

    /** Load a model from configuration (delegates to registerChatModel with existing ChatModel). */
    public ModelInfo loadModel(ModelConfig cfg) {
        if (cfg.getType() != ModelType.CHAT) {
            throw new ModelLoadError("Only CHAT models can be loaded into the ChatModel runtime: "
                    + cfg.getName(), (Throwable) null);
        }
        // Find the matching ChatModel by provider
        String provider = cfg.getProvider() != null ? cfg.getProvider() : cfg.getBackend();
        if (provider == null || provider.isBlank() || "auto".equals(provider)) {
            // Pick the first available ChatModel
            if (!chatModels.isEmpty()) {
                provider = chatModels.keySet().iterator().next();
            }
        }
        ChatModel cm = chatModels.get(provider);
        if (cm == null) {
            // Also try matching by provider field in registered models
            for (var entry : models.entrySet()) {
                if (provider != null && provider.equals(entry.getValue().getProvider())) {
                    cm = chatModels.get(entry.getKey());
                    break;
                }
            }
            if (cm == null && !chatModels.isEmpty()) {
                // Fall back to any available ChatModel
                cm = chatModels.values().iterator().next();
            }
            if (cm == null) {
                throw new ModelLoadError("No ChatModel available for model " + cfg.getName(),
                        (Throwable) null);
            }
        }
        chatModels.put(cfg.getName(), cm);
        ModelInfo info = ModelInfo.fromConfig(cfg);
        info.setStatus("loaded");
        info.setLoadedAt(Instant.now());
        // Set provider from the ChatModel's own metadata
        if (info.getProvider() == null) {
            info.setProvider(detectProvider(cm));
        }
        models.put(cfg.getName(), info);
        log.info("Model loaded: {} provider={}", cfg.getName(), info.getProvider());
        return info;
    }

    /** Unload a model. */
    public void unloadModel(String name) {
        chatModels.remove(name);
        models.remove(name);
        log.info("Model unloaded: {}", name);
    }

    /** Run inference through the registered ChatModel with pre/post-processing pipelines. */
    public PredictResponse predict(PredictRequest request) {
        String modelName = resolveModelName(request.getModelName());
        ChatModel cm = chatModels.get(modelName);
        if (cm == null) {
            throw new ModelNotFoundError("Model not loaded: " + modelName);
        }
        request.setModelName(modelName);
        PredictRequest processed = pipelineManager.applyPreProcessing(modelName, request);
        if (processed == null) {
            throw new PredictionError(
                    "Request rejected by pre-processing pipeline for model " + modelName);
        }

        long t0 = System.nanoTime();
        Prompt prompt = new Prompt(new UserMessage(processed.getText()));
        ChatResponse chatResponse = cm.call(prompt);
        long elapsed = System.nanoTime() - t0;

        String content = chatResponse.getResult().getOutput().getText();
        PredictResponse response = PredictResponse.ok(content, modelName,
                elapsed / 1_000_000_000.0);

        PredictResponse postProcessed = pipelineManager.applyPostProcessing(modelName, response);
        return postProcessed != null ? postProcessed : response;
    }

    /** List all registered models. */
    public List<ModelInfo> listModels() {
        return List.copyOf(models.values());
    }

    /** Get info for a specific model. */
    public ModelInfo getModel(String name) {
        ModelInfo info = models.get(name);
        if (info == null) {
            throw new ModelNotFoundError("Model not found: " + name);
        }
        return info;
    }

    /** Unload all models. */
    public void shutdown() {
        new ArrayList<>(models.keySet()).forEach(this::unloadModel);
        log.info("ModelRegistry shutdown complete");
    }

    /** Round-trip benchmark helper. */
    public PredictResponse benchmarkPredict(PredictRequest request) {
        return predict(request);
    }

    private String resolveModelName(String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        if (activeModel != null) {
            return activeModel;
        }
        var loaded = new ArrayList<>(models.keySet());
        if (loaded.isEmpty()) {
            throw new ModelNotFoundError("No models are loaded");
        }
        if (loaded.size() > 1) {
            throw new ModelNotFoundError(
                    "Multiple models loaded; specify model name. Available: " + loaded);
        }
        return loaded.get(0);
    }

    private static String detectProvider(ChatModel cm) {
        String className = cm.getClass().getSimpleName();
        if (className.toLowerCase().contains("ollama")) return "ollama";
        if (className.toLowerCase().contains("openai")) return "openai";
        if (className.toLowerCase().contains("vertex")) return "vertex";
        return className;
    }
}
