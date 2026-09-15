package com.xnlp.core.runtime;

import com.xnlp.core.api.NlpContext;

/**
 * Project-level SPI for external NLP runtimes.
 *
 * <p>Implementations own model validation, loading, bounded execution and native resource cleanup.
 */
public interface NlpRuntime extends AutoCloseable {

    NlpRuntimeDescriptor descriptor();

    NlpRuntimeStatus status();

    default boolean supports(String capability) {
        return descriptor().supports(capability);
    }

    void load();

    NlpRuntimeResult execute(String capability, NlpContext context);

    @Override
    void close();
}
