package com.xnlp.server.service;

import com.xnlp.core.errors.ModelNotFoundError;
import com.xnlp.core.model.PredictResponse;
import com.xnlp.core.registry.ModelRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BenchmarkServiceTest {

    @Test
    void benchmark_allRequestsSucceed_reportsCountsAndPercentiles() {
        ModelRegistry registry = mock(ModelRegistry.class);
        MetricsService metrics = mock(MetricsService.class);
        when(registry.benchmarkPredict(any())).thenReturn(PredictResponse.ok("ok", "demo", 0));

        var result = new BenchmarkService(registry, metrics).benchmark("demo", 10, 2, "hello");

        assertThat(result.getTotalRequests()).isEqualTo(10);
        assertThat(result.getSuccessfulRequests()).isEqualTo(10);
        assertThat(result.getFailedRequests()).isZero();
        assertThat(result.getSuccessRate()).isEqualTo(1.0);
        assertThat(result.getFailureCode()).isNull();
        assertThat(result.getLatenciesMs()).hasSize(10);
        assertThat(result.getLatencyP95Ms()).isGreaterThanOrEqualTo(result.getLatencyP50Ms());
        assertThat(result.getLatencyP99Ms()).isGreaterThanOrEqualTo(result.getLatencyP95Ms());
    }

    @Test
    void benchmark_partialFailures_keepsTotalAndUsesSuccessfulLatenciesOnly() {
        ModelRegistry registry = mock(ModelRegistry.class);
        MetricsService metrics = mock(MetricsService.class);
        AtomicInteger calls = new AtomicInteger();
        when(registry.benchmarkPredict(any())).thenAnswer(invocation -> {
            if (calls.getAndIncrement() % 2 == 0) {
                throw new RuntimeException("provider details must not escape");
            }
            return PredictResponse.ok("ok", "demo", 0);
        });

        var result = new BenchmarkService(registry, metrics).benchmark("demo", 8, 2, "hello");

        assertThat(result.getTotalRequests()).isEqualTo(8);
        assertThat(result.getSuccessfulRequests()).isEqualTo(4);
        assertThat(result.getFailedRequests()).isEqualTo(4);
        assertThat(result.getSuccessRate()).isEqualTo(0.5);
        assertThat(result.getLatenciesMs()).hasSize(4);
        assertThat(result.getFailureCode()).isEqualTo("BENCHMARK_REQUEST_FAILED");
        assertThat(result.getFailureMessage()).doesNotContain("provider details");
    }

    @Test
    void benchmark_allRequestsFail_returnsExplicitFailureResult() {
        ModelRegistry registry = mock(ModelRegistry.class);
        MetricsService metrics = mock(MetricsService.class);
        when(registry.benchmarkPredict(any())).thenThrow(new ModelNotFoundError("secret model detail"));

        var result = new BenchmarkService(registry, metrics).benchmark("missing", 3, 1, "hello");

        assertThat(result.getTotalRequests()).isEqualTo(3);
        assertThat(result.getSuccessfulRequests()).isZero();
        assertThat(result.getFailedRequests()).isEqualTo(3);
        assertThat(result.getSuccessRate()).isZero();
        assertThat(result.getLatenciesMs()).isEmpty();
        assertThat(result.getLatencyP50Ms()).isZero();
        assertThat(result.getLatencyP95Ms()).isZero();
        assertThat(result.getLatencyP99Ms()).isZero();
        assertThat(result.getFailureCode()).isEqualTo("MODEL_NOT_FOUND");
        assertThat(result.getFailureMessage()).isEqualTo("The selected model is not loaded.");
    }

    @Test
    void percentile_usesNearestRankAndHandlesEmptyInput() {
        assertThat(BenchmarkService.percentile(List.of(10d, 20d, 30d, 40d), 0.50)).isEqualTo(20d);
        assertThat(BenchmarkService.percentile(List.of(10d, 20d, 30d, 40d), 0.95)).isEqualTo(40d);
        assertThat(BenchmarkService.percentile(List.of(), 0.99)).isZero();
    }
}
