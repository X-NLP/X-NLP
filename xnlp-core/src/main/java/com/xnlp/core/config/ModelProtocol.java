package com.xnlp.core.config;

import java.util.EnumSet;
import java.util.Set;

/**
 * Supported standard protocols for external model providers.
 */
public enum ModelProtocol {
    SPRING_AI_CHAT(ModelType.CHAT),
    OPENAI_CHAT_COMPLETIONS(ModelType.CHAT),
    OLLAMA_CHAT(ModelType.CHAT),
    ANTHROPIC_MESSAGES(ModelType.CHAT),
    GOOGLE_GEMINI_GENERATE_CONTENT(ModelType.CHAT),

    SPRING_AI_EMBEDDING(ModelType.EMBEDDING),
    OPENAI_EMBEDDINGS(ModelType.EMBEDDING),
    OLLAMA_EMBEDDINGS(ModelType.EMBEDDING),
    GOOGLE_GEMINI_EMBEDDING(ModelType.EMBEDDING),

    COHERE_RERANK(ModelType.RERANKING),
    JINA_RERANK(ModelType.RERANKING);

    private final ModelType modelType;

    ModelProtocol(ModelType modelType) {
        this.modelType = modelType;
    }

    public ModelType getModelType() {
        return modelType;
    }

    public static Set<ModelProtocol> forType(ModelType modelType) {
        EnumSet<ModelProtocol> protocols = EnumSet.noneOf(ModelProtocol.class);
        for (ModelProtocol protocol : values()) {
            if (protocol.modelType == modelType) {
                protocols.add(protocol);
            }
        }
        return protocols;
    }
}
