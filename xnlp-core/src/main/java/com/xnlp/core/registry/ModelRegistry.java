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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for model management.
 *
 * <p>Maintains a mapping between model names and their Spring AI
 * {@link ChatModel} instances. Predictions flow through the configured
 * {@link PipelineManager} for pre/post-processing.</p>
 *
 * <p>When a scope resolver is supplied, provider runtimes registered by the
 * server are global, while models loaded from tenant-specific profiles are
 * isolated to the current scope. The no-argument constructors retain the
 * original single-tenant behavior for embedders and tests.</p>
 */
public class ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);

    private final Map<String, ModelInfo> models = new ConcurrentHashMap<>();
    private final Map<String, ChatModel> chatModels = new ConcurrentHashMap<>();
    private final Map<String, ModelInfo> globalModels = new ConcurrentHashMap<>();
    private final Map<String, ChatModel> globalChatModels = new ConcurrentHashMap<>();
    private final PipelineManager pipelineManager = new PipelineManager();
    private final String activeModel;
    private final Supplier<String> scopeResolver;

    public ModelRegistry() {
        this(null, null);
    }

    public ModelRegistry(String activeModel) {
        this(activeModel, null);
    }

    /** Create a registry whose loaded model names are isolated by the resolved scope. */
    public ModelRegistry(String activeModel, Supplier<String> scopeResolver) {
        this.activeModel = activeModel;
        this.scopeResolver = scopeResolver;
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
     * Register a provider runtime. Provider runtimes are intentionally global
     * in a scoped registry so every tenant can load a profile against the same
     * Spring AI ChatModel bean without sharing tenant model metadata.
     */
    public ModelInfo registerChatModel(String name, ChatModel chatModel, ModelInfo info) {
        info.setStatus("loaded");
        info.setLoadedAt(Instant.now());
        if (!isScoped()) {
            chatModels.put(name, chatModel);
            models.put(name, info);
        } else {
            globalChatModels.put(name, chatModel);
            globalModels.put(name, info);
        }
        log.info("Registered ChatModel: name={} provider={}", name, info.getProvider());
        return info;
    }

    /** Load a model from configuration using an available Spring AI ChatModel. */
    public ModelInfo loadModel(ModelConfig cfg) {
        if (cfg.getType() != ModelType.CHAT) {
            throw new ModelLoadError("Only CHAT models can be loaded into the ChatModel runtime: "
                    + cfg.getName(), (Throwable) null);
        }
        String provider = cfg.getProvider() != null ? cfg.getProvider() : cfg.getBackend();
        Map<String, ChatModel> availableChatModels = visibleChatModels();
        if (provider == null || provider.isBlank() || "auto".equals(provider)) {
            if (!availableChatModels.isEmpty()) provider = availableChatModels.keySet().iterator().next();
        }
        ChatModel cm = availableChatModels.get(provider);
        if (cm == null) {
            for (var entry : visibleModels().entrySet()) {
                if (provider != null && provider.equals(entry.getValue().getProvider())) {
                    cm = availableChatModels.get(entry.getKey());
                    break;
                }
            }
            if (cm == null && !availableChatModels.isEmpty()) {
                cm = availableChatModels.values().iterator().next();
            }
            if (cm == null) {
                throw new ModelLoadError("No ChatModel available for model " + cfg.getName(),
                        (Throwable) null);
            }
        }
        ModelInfo info = ModelInfo.fromConfig(cfg);
        info.setStatus("loaded");
        info.setLoadedAt(Instant.now());
        if (info.getProvider() == null) info.setProvider(detectProvider(cm));
        if (!isScoped()) {
            chatModels.put(cfg.getName(), cm);
            models.put(cfg.getName(), info);
        } else {
            chatModels.put(scopedKey(cfg.getName()), cm);
            models.put(scopedKey(cfg.getName()), info);
        }
        log.info("Model loaded: scope={} name={} provider={}", currentScope(), cfg.getName(), info.getProvider());
        return info;
    }

    /** Unload a model from the current scope, or a global runtime in legacy mode. */
    public void unloadModel(String name) {
        if (!isScoped()) {
            chatModels.remove(name);
            models.remove(name);
        } else {
            chatModels.remove(scopedKey(name));
            models.remove(scopedKey(name));
        }
        log.info("Model unloaded: scope={} name={}", currentScope(), name);
    }

    /** Run inference through the registered ChatModel with pre/post-processing pipelines. */
    public PredictResponse predict(PredictRequest request) {
        String modelName = resolveModelName(request.getModelName());
        ChatModel cm = visibleChatModels().get(modelName);
        if (cm == null) throw new ModelNotFoundError("Model not loaded: " + modelName);
        request.setModelName(modelName);
        PredictRequest processed = pipelineManager.applyPreProcessing(modelName, request);
        if (processed == null) {
            throw new PredictionError("Request rejected by pre-processing pipeline for model " + modelName);
        }
        long t0 = System.nanoTime();
        ChatResponse chatResponse = cm.call(new Prompt(new UserMessage(processed.getText())));
        long elapsed = System.nanoTime() - t0;
        String content = chatResponse.getResult().getOutput().getText();
        PredictResponse response = PredictResponse.ok(content, modelName, elapsed / 1_000_000_000.0);
        PredictResponse postProcessed = pipelineManager.applyPostProcessing(modelName, response);
        return postProcessed != null ? postProcessed : response;
    }

    /** List global provider runtimes and models loaded for the current scope. */
    public List<ModelInfo> listModels() {
        return List.copyOf(visibleModels().values());
    }

    /** Resolve a registered chat runtime by name, preferring the current scope. */
    public ChatModel getChatModel(String name) {
        if (name == null) return null;
        if (!isScoped()) return chatModels.get(name);
        ChatModel scoped = chatModels.get(scopedKey(name));
        return scoped != null ? scoped : globalChatModels.get(name);
    }

    /** Get info for a specific model, preferring the current scope. */
    public ModelInfo getModel(String name) {
        ModelInfo info = visibleModels().get(name);
        if (info == null) throw new ModelNotFoundError("Model not found: " + name);
        return info;
    }

    /** Unload all models and clear provider runtimes. */
    public void shutdown() {
        chatModels.clear();
        models.clear();
        globalChatModels.clear();
        globalModels.clear();
        log.info("ModelRegistry shutdown complete");
    }

    public PredictResponse benchmarkPredict(PredictRequest request) {
        return predict(request);
    }

    private String resolveModelName(String requested) {
        if (requested != null && !requested.isBlank()) return requested;
        if (activeModel != null) return activeModel;
        var loaded = new ArrayList<>(visibleModels().keySet());
        if (loaded.isEmpty()) throw new ModelNotFoundError("No models are loaded");
        if (loaded.size() > 1) {
            throw new ModelNotFoundError("Multiple models loaded; specify model name. Available: " + loaded);
        }
        return loaded.getFirst();
    }

    private Map<String, ModelInfo> visibleModels() {
        if (!isScoped()) return models;
        Map<String, ModelInfo> visible = new LinkedHashMap<>(globalModels);
        String prefix = currentScope() + "\0";
        models.forEach((key, value) -> {
            if (key.startsWith(prefix)) visible.put(unscopedKey(key), value);
        });
        return visible;
    }

    private Map<String, ChatModel> visibleChatModels() {
        if (!isScoped()) return chatModels;
        Map<String, ChatModel> visible = new LinkedHashMap<>(globalChatModels);
        String prefix = currentScope() + "\0";
        chatModels.forEach((key, value) -> {
            if (key.startsWith(prefix)) visible.put(unscopedKey(key), value);
        });
        return visible;
    }

    private boolean isScoped() {
        return scopeResolver != null;
    }

    private String currentScope() {
        return isScoped() ? scopeResolver.get() : "default";
    }

    private String scopedKey(String name) {
        return currentScope() + "\0" + name;
    }

    private static String unscopedKey(String key) {
        int separator = key.indexOf('\0');
        return separator < 0 ? key : key.substring(separator + 1);
    }

    private static String detectProvider(ChatModel cm) {
        String className = cm.getClass().getSimpleName().toLowerCase();
        if (className.contains("ollama")) return "ollama";
        if (className.contains("openai")) return "openai";
        if (className.contains("vertex")) return "vertex";
        return cm.getClass().getSimpleName();
    }
}
