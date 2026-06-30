package com.xnlp.core.pipeline;

import com.xnlp.core.model.PredictRequest;
import com.xnlp.core.model.PredictResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages the ordered execution of {@link ProcessingPipeline} instances.
 *
 * <p>Pipelines are registered and executed in priority order (lower = earlier).
 * Pre-processing runs lowest-to-highest; post-processing runs reverse
 * (highest last = LIFO on the output side).
 */
public class PipelineManager {

    private static final Logger log = LoggerFactory.getLogger(PipelineManager.class);

    private final List<ProcessingPipeline> pipelines = new CopyOnWriteArrayList<>();

    public void register(ProcessingPipeline pipeline) {
        pipelines.add(pipeline);
        log.info("Registered pipeline: {} (priority={})", pipeline.name(), pipeline.priority());
    }

    public void unregister(ProcessingPipeline pipeline) {
        pipelines.remove(pipeline);
    }

    public List<ProcessingPipeline> getPipelines() {
        return List.copyOf(pipelines);
    }

    /** Apply pre-processing for the given model. Returns modified request or null to skip. */
    public PredictRequest applyPreProcessing(String modelName, PredictRequest request) {
        PredictRequest current = request;
        for (ProcessingPipeline p : sortedByPriority()) {
            if (p.supports(modelName)) {
                PredictRequest transformed = p.preProcess(current);
                if (transformed == null) {
                    log.debug("Pipeline '{}' skipped request for model '{}'", p.name(), modelName);
                    return null;
                }
                current = transformed;
            }
        }
        return current;
    }

    /** Apply post-processing for the given model. Returns modified response or null to swallow. */
    public PredictResponse applyPostProcessing(String modelName, PredictResponse response) {
        PredictResponse current = response;
        // Post-process in reverse priority order: highest priority first
        var reversed = new ArrayList<>(sortedByPriority());
        java.util.Collections.reverse(reversed);
        for (ProcessingPipeline p : reversed) {
            if (p.supports(modelName)) {
                PredictResponse transformed = p.postProcess(current);
                if (transformed == null) {
                    log.debug("Pipeline '{}' swallowed response for model '{}'", p.name(), modelName);
                    return null;
                }
                current = transformed;
            }
        }
        return current;
    }

    private List<ProcessingPipeline> sortedByPriority() {
        var sorted = new ArrayList<>(pipelines);
        sorted.sort(Comparator.comparingInt(ProcessingPipeline::priority));
        return sorted;
    }
}
