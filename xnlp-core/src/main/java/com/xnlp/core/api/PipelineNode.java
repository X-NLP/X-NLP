package com.xnlp.core.api;

import java.util.Collections;
import java.util.List;

/**
 * A node in a processing pipeline that wires an {@link NlpComponent}
 * together with its upstream dependencies and output routing.
 *
 * <p>Pipelines are DAGs: a node may depend on zero or more upstream
 * nodes, and its output can feed into zero or more downstream nodes.
 * This enables complex NLP graphs such as:
 * <pre>
 *   [Tokenizer] -> [POS Tagger] -> [NER] -> [Merge]
 *   [Tokenizer] -> [Dependency Parser] --------^
 * </pre>
 */
public interface PipelineNode {

    /** Stable identifier within the pipeline, for example tok, pos, ner. */
    String nodeId();

    /** The NLP component executed at this node. */
    NlpComponent component();

    /** Node IDs that this node depends on (upstream). */
    default List<String> upstream() { return Collections.emptyList(); }

    /** Whether this node should be executed. */
    default boolean enabled() { return true; }
}
