package com.xnlp.server.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Central observability service for custom business metrics.
 *
 * <p>Exposes counters, timers, and gauges for predict and benchmark
 * operations, tagged by model name.  All meters are registered with
 * the Micrometer {@link MeterRegistry} so they appear on the
 * {@code /actuator/prometheus} endpoint automatically.
 */
@Service
public class MetricsService {

    private static final Logger log = LoggerFactory.getLogger(MetricsService.class);

    private final MeterRegistry registry;

    /** Predict request counter, tagged by {@code model} and {@code status}. */
    private final ConcurrentHashMap<String, Counter> predictCounters = new ConcurrentHashMap<>();

    /** Predict latency timer, tagged by {@code model}. */
    private final ConcurrentHashMap<String, Timer> predictTimers = new ConcurrentHashMap<>();

    /** Benchmark latency timer, tagged by {@code model}. */
    private final ConcurrentHashMap<String, Timer> benchmarkTimers = new ConcurrentHashMap<>();

    /** Number of models currently loaded. */
    private final AtomicInteger loadedModelCount = new AtomicInteger(0);

    public MetricsService(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("xnlp.models.loaded", loadedModelCount, AtomicInteger::get)
                .description("Number of NLP models currently loaded")
                .register(registry);
        log.info("Custom Micrometer metrics registered: xnlp.predict.count, xnlp.predict.latency, "
                + "xnlp.benchmark.latency, xnlp.models.loaded");
    }

    // ---- Model lifecycle ----

    public void incrementLoadedModels() {
        loadedModelCount.incrementAndGet();
    }

    public void decrementLoadedModels() {
        loadedModelCount.decrementAndGet();
    }

    public void setLoadedModelCount(int count) {
        loadedModelCount.set(count);
    }

    // ---- Predict metrics ----

    /**
     * Record a completed prediction.
     *
     * @param modelName  the model that served the request
     * @param success    whether the prediction succeeded
     * @param nanos      elapsed wall-clock time in nanoseconds
     */
    public void recordPredict(String modelName, boolean success, long nanos) {
        predictCounter(modelName, success ? "success" : "error").increment();
        predictTimer(modelName).record(nanos, TimeUnit.NANOSECONDS);
    }

    // ---- Benchmark metrics ----

    public Timer.Sample startBenchmarkTimer() {
        return Timer.start(registry);
    }

    public void stopBenchmarkTimer(String modelName, Timer.Sample sample) {
        sample.stop(benchmarkTimer(modelName));
    }

    // ---- Internal helpers ----

    private Counter predictCounter(String model, String status) {
        return predictCounters.computeIfAbsent(model + ":" + status, k ->
                Counter.builder("xnlp.predict.count")
                        .description("Total prediction requests")
                        .tags("model", model, "status", status)
                        .register(registry));
    }

    private Timer predictTimer(String model) {
        return predictTimers.computeIfAbsent(model, k ->
                Timer.builder("xnlp.predict.latency")
                        .description("Prediction latency")
                        .tags("model", model)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .publishPercentileHistogram(true)
                        .register(registry));
    }

    private Timer benchmarkTimer(String model) {
        return benchmarkTimers.computeIfAbsent(model, k ->
                Timer.builder("xnlp.benchmark.latency")
                        .description("Benchmark per-request latency")
                        .tags("model", model)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .publishPercentileHistogram(true)
                        .register(registry));
    }
}
