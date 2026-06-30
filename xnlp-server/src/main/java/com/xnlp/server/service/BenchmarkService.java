package com.xnlp.server.service;

import com.xnlp.core.model.BenchmarkResult;
import com.xnlp.core.model.PredictRequest;
import com.xnlp.core.model.PredictResponse;
import com.xnlp.core.registry.ModelRegistry;
import io.micrometer.observation.annotation.Observed;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class BenchmarkService {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkService.class);

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
        long start = System.currentTimeMillis();

        for (int i = 0; i < totalRequests; i++) {
            PredictRequest req = new PredictRequest();
            req.setModelName(modelName);
            req.setText(text);
            executor.submit(() -> {
                Timer.Sample sample = metrics.startBenchmarkTimer();
                long t0 = System.nanoTime();
                PredictResponse resp = registry.benchmarkPredict(req);
                long elapsed = System.nanoTime() - t0;
                latencies.add(elapsed / 1_000_000.0);
                resp.setElapsedSeconds(elapsed / 1_000_000_000.0);
                metrics.stopBenchmarkTimer(modelName, sample);
                return resp;
            });
        }

        executor.shutdown();
        try {
            executor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        double elapsedSec = (System.currentTimeMillis() - start) / 1000.0;

        if (!latencies.isEmpty()) {
            List<Double> sorted = latencies.stream().sorted().toList();
            BenchmarkResult r = new BenchmarkResult();
            r.setModel(modelName);
            r.setTotalRequests(totalRequests);
            r.setRequestsPerSecond(totalRequests / Math.max(elapsedSec, 0.001));
            r.setLatencyAvgMs(latencies.stream().mapToDouble(d -> d).average().orElse(0));
            r.setLatencyP50Ms(percentile(sorted, 0.50));
            r.setLatencyP99Ms(percentile(sorted, 0.99));
            r.setLatenciesMs(latencies);
            log.info("Benchmark done: model={} rps={} avgMs={}",
                    modelName, String.format("%.1f", r.getRequestsPerSecond()),
                    String.format("%.2f", r.getLatencyAvgMs()));
            return r;
        }

        BenchmarkResult empty = new BenchmarkResult();
        empty.setModel(modelName);
        empty.setTotalRequests(0);
        return empty;
    }

    private static double percentile(List<Double> sorted, double pct) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(pct * sorted.size()) - 1;
        return sorted.get(Math.max(idx, 0));
    }
}
