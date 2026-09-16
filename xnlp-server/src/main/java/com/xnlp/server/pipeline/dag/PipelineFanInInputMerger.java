package com.xnlp.server.pipeline.dag;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministically combines run input and predecessor outputs for a node.
 *
 * <p>The run input is copied first in key order. Predecessors are then applied in ascending
 * {@code nodeId} order, with each output copied in key order. On a key collision, the value from
 * the lexicographically later predecessor wins; any predecessor value wins over the run input.
 * This makes fan-in independent of parallel completion order while retaining the flat input map
 * expected by capability executors.</p>
 */
public final class PipelineFanInInputMerger {

    public Map<String, Object> merge(
            Map<String, Object> runInput,
            Map<String, ? extends Map<String, Object>> predecessorOutputs) {
        if (runInput == null) {
            throw PipelineDagException.invalid("Pipeline run input must not be null");
        }
        if (predecessorOutputs == null) {
            throw PipelineDagException.invalid("Pipeline predecessor outputs must not be null");
        }

        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        copySorted(runInput, merged, "Pipeline run input");
        for (Map.Entry<String, ? extends Map<String, Object>> entry
                : new TreeMap<>(predecessorOutputs).entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw PipelineDagException.invalid("Pipeline predecessor nodeId must not be blank");
            }
            if (entry.getValue() == null) {
                throw PipelineDagException.invalid(
                        "Pipeline predecessor output must not be null: " + entry.getKey());
            }
            copySorted(entry.getValue(), merged, "Pipeline predecessor output " + entry.getKey());
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(merged));
    }

    private void copySorted(
            Map<String, Object> source,
            LinkedHashMap<String, Object> target,
            String sourceDescription) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        source.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw PipelineDagException.invalid(sourceDescription + " contains a blank key");
            }
            sorted.put(key, value);
        });
        sorted.forEach(target::put);
    }
}
