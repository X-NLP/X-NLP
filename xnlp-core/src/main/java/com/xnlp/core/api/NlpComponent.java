package com.xnlp.core.api;

import java.util.Map;

/**
 * SPI for an NLP processing component.
 *
 * <p>Each NLP capability (tokenization, POS tagging, NER, parsing, etc.) is
 * modeled as a self-contained {@code NlpComponent}. Components are discovered
 * and wired by the {@code NlpCapabilityRegistry} and executed by the
 * {@code NlpAnalysisService}.
 *
 * <p>This pattern is inspired by HanLP's component model and Haystack's
 * composable node design.
 */
public interface NlpComponent {

    /** Unique capability identifier, for example TOK, POS, NER, DEP. */
    String id();

    /** Human-readable name for the capability. */
    String displayName();

    /** Short description of what this component does. */
    String description();

    /** Expected input parameter schema (keys and their descriptions). */
    Map<String, String> parameterSchema();

    /**
     * Execute this NLP component with the given parameters.
     *
     * @param context execution context containing input text and task parameters
     * @return structured result specific to this component
     */
    ComponentResult execute(NlpContext context);
}
