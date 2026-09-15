package com.xnlp.server.service;

import com.xnlp.core.errors.ModelNotFoundError;
import com.xnlp.core.errors.PredictionError;
import com.xnlp.core.model.BenchmarkResult;
import com.xnlp.core.model.PredictRequest;
import com.xnlp.core.model.PredictResponse;
import com.xnlp.core.registry.ModelRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Benchmark service that stress-tests model inference throughput and latency.
 *
 * <p>Every submitted request is accounted for, including requests that fail or
 * are interrupted after the bounded benchmark wait. Latency percentiles are
 * calculated from successful requests only, so a failed provider call cannot
 * make the model appear slower or faster than it really was.</p>
 */
@Service
public class BenchmarkService {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkService.class);
    private static final long AWAIT_TIMEOUT_SECONDS = 60;

    private final ModelRegistry registry;
    private final MetricsService metrics;

    public BenchmarkService(ModelRegistry registry, MetricsService metrics) {
        this.registry = registry;
        this.metrics = metrics;
    }

    @Observed(name = "xnlp.benchmark",
              contextualName = "benchmark",
              lowCardinalityKeyValues = {"component", "benchmark"})
    public BenchmarkResult benchmark(String modelName, int totalRequests,
                                     int concurrency, String text) {
        log.info("Benchmark start: model={} requests={} concurrency={}", modelName, totalRequests, concurrency);

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<Double> latencies = new CopyOnWriteArrayList<>();
        AtomicInteger successfulRequests = new AtomicInteger();
        AtomicInteger failedRequests = new AtomicInteger();
        AtomicReference<String> failureCode = new AtomicReference<>();
        long start = System.currentTimeMillis();

        for (int i = 0; i < totalRequests; i++) {
            PredictRequest req = new PredictRequest();
            req.setModelName(modelName);
            req.setText(text);
            executor.submit(() -> runSingleRequest(req, latencies, successfulRequests,
                    failedRequests, failureCode));
        }

        executor.shutdown();
        boolean completed = await(executor);
        if (!completed) {
            executor.shutdownNow();
            int accounted = successfulRequests.get() + failedRequests.get();
            int interrupted = Math.max(totalRequests - accounted, 0);
            failedRequests.addAndGet(interrupted);
            failureCode.compareAndSet(null, "BENCHMARK_TIMEOUT");
        }

        // A task can be interrupted at the timeout boundary after the first
        // accounting pass. Keep the public result internally consistent.
        int successful = Math.min(successfulRequests.get(), totalRequests);
        int failed = Math.max(totalRequests - successful, 0);
        if (failedRequests.get() > failed) {
            failed = Math.min(failedRequests.get(), totalRequests - successful);
        }
        failedRequests.set(failed);

        double elapsedSec = (System.currentTimeMillis() - start) / 1000.0;
        List<Double> sorted = latencies.stream().sorted().toList();
        BenchmarkResult result = new BenchmarkResult();
        result.setModel(modelName);
        result.setTotalRequests(totalRequests);
        result.setSuccessfulRequests(successful);
        result.setFailedRequests(failed);
        result.setSuccessRate(totalRequests == 0 ? 0 : (double) successful / totalRequests);
        result.setRequestsPerSecond(totalRequests / Math.max(elapsedSec, 0.001));
        result.setLatencyAvgMs(latencies.stream().mapToDouble(d -> d).average().orElse(0));
        result.setLatencyP50Ms(percentile(sorted, 0.50));
        result.setLatencyP95Ms(percentile(sorted, 0.95));
        result.setLatencyP99Ms(percentile(sorted, 0.99));
        result.setLatenciesMs(latencies);

        if (failed > 0) {
            String code = failureCode.get();
            result.setFailureCode(code == null ? "BENCHMARK_REQUEST_FAILED" : code);
            result.setFailureMessage(failureMessage(result.getFailureCode()));
        }

        log.info("Benchmark done: model={} requests={} successful={} failed={} rps={} avgMs={}",
                modelName, totalRequests, successful, failed,
                String.format("%.1f", result.getRequestsPerSecond()),
                String.format("%.2f", result.getLatencyAvgMs()));
        return result;
    }

    private void runSingleRequest(PredictRequest request, List<Double> latencies,
                                  AtomicInteger successfulRequests, AtomicInteger failedRequests,
                                  AtomicReference<String> failureCode) {
        Timer.Sample sample = metrics.startBenchmarkTimer();
        long t0 = System.nanoTime();
        try {
            PredictResponse response = registry.benchmarkPredict(request);
            if (response == null) {
                throw new PredictionError("Benchmark prediction returned no response");
            }
            long elapsed = System.nanoTime() - t0;
            latencies.add(elapsed / 1_000_000.0);
            response.setElapsedSeconds(elapsed / 1_000_000_000.0);
            successfulRequests.incrementAndGet();
        } catch (RuntimeException error) {
            failedRequests.incrementAndGet();
            failureCode.compareAndSet(null, failureCode(error));
            log.debug("Benchmark request failed: model={} code={}", request.getModelName(), failureCode(error));
        } finally {
            metrics.stopBenchmarkTimer(request.getModelName(), sample);
        }
    }

    private static boolean await(ExecutorService executor) {
        try {
            return executor.awaitTermination(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String failureCode(Throwable error) {
        if (error instanceof ModelNotFoundError) return "MODEL_NOT_FOUND";
        if (error instanceof PredictionError) return "PREDICTION_ERROR";
        return "BENCHMARK_REQUEST_FAILED";
    }

    private static String failureMessage(String code) {
        return switch (code) {
            case "MODEL_NOT_FOUND" -> "The selected model is not loaded.";
            case "PREDICTION_ERROR" -> "The model rejected one or more benchmark requests.";
            case "BENCHMARK_TIMEOUT" -> "The benchmark exceeded its 60 second execution limit.";
            default -> "One or more benchmark requests failed.";
        };
    }

    static double percentile(List<Double> sorted, double pct) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(pct * sorted.size()) - 1;
        return sorted.get(Math.max(idx, 0));
    }
}
