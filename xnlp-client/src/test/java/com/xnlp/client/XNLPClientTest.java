package com.xnlp.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xnlp.core.config.ModelType;
import com.xnlp.core.model.BenchmarkResult;
import com.xnlp.core.model.ModelInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XNLPClientTest {

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/health", exchange -> respond(exchange, 200,
                "{\"app\":\"xnlp-server\",\"status\":\"UP\"}"));
        server.createContext("/api/v1/models/space model/unload", exchange -> respond(exchange, 204, ""));
        server.createContext("/api/v1/models/space model", exchange -> respond(exchange, 200,
                "{\"name\":\"space model\",\"version\":\"v1\",\"status\":\"loaded\",\"loadedAt\":\"2026-09-14T10:15:30Z\"}"));
        server.createContext("/api/v1/models/missing", exchange -> respond(exchange, 404,
                "{\"message\":\"model not found\"}"));
        server.createContext("/api/v1/models/diagnostics", exchange -> respond(exchange, 200,
                "{\"checkedAt\":\"2026-09-15T09:00:00Z\",\"activeProbe\":false,"
                        + "\"diagnostics\":[{\"name\":\"openai-chat\",\"type\":\"CHAT\","
                        + "\"protocol\":\"OPENAI_CHAT_COMPLETIONS\",\"provider\":\"openai\","
                        + "\"model\":\"gpt-test\",\"endpoint\":\"https://example.invalid/v1\","
                        + "\"configured\":true,\"reachable\":null,\"usable\":null,"
                        + "\"status\":\"CONFIGURED\",\"elapsedMillis\":0}]}"));
        server.createContext("/api/v1/models/diagnostics/probe", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, 200,
                    "{\"checkedAt\":\"2026-09-15T09:01:00Z\",\"activeProbe\":true,\"diagnostics\":[]}");
        });
        server.createContext("/api/v1/benchmark/test-model", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200,
                    "{\"model\":\"test-model\",\"totalRequests\":3,\"successfulRequests\":3,"
                            + "\"failedRequests\":0,\"successRate\":100.0,\"requestsPerSecond\":12.5,"
                            + "\"latencyAvgMs\":20.0,\"latencyP50Ms\":18.0,\"latencyP95Ms\":25.0,"
                            + "\"latencyP99Ms\":27.0,\"latenciesMs\":[18.0,20.0,27.0]}");
        });
        server.createContext("/api/v1/models/stable-error", exchange -> respond(exchange, 503,
                "{\"timestamp\":\"2026-09-15T09:02:00Z\",\"status\":503,"
                        + "\"error\":\"PROVIDER_UNAVAILABLE\",\"message\":\"provider unavailable\","
                        + "\"requestId\":\"req-123\",\"traceId\":\"trace-456\"}"));
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void health_usesHumanHealthEndpointAndConfiguredHeaders() {
        server.removeContext("/health");
        server.createContext("/health", exchange -> {
            requests.add(exchange.getRequestURI() + " " + exchange.getRequestHeaders().getFirst("X-API-Key")
                    + " " + exchange.getRequestHeaders().getFirst("X-Tenant-ID"));
            respond(exchange, 200, "{\"status\":\"UP\"}");
        });

        try (XNLPClient client = XNLPClient.builder(baseUrl())
                .apiKey("secret")
                .tenantId("team-a")
                .build()) {
            assertThat(client.health()).containsEntry("status", "UP");
        }

        assertThat(requests).containsExactly("/health secret team-a");
    }

    @Test
    void getModel_decodesJavaTimeAndEscapesPathSegments() {
        try (XNLPClient client = new XNLPClient(baseUrl())) {
            ModelInfo model = client.getModel("space model");
            assertThat(model.getName()).isEqualTo("space model");
            assertThat(model.getLoadedAt()).hasToString("2026-09-14T10:15:30Z");
        }
    }

    @Test
    void unloadModel_usesRuntimeUnloadPostWithoutDeletingProfile() {
        try (XNLPClient client = new XNLPClient(baseUrl())) {
            client.unloadModel("space model");
        }
    }

    @Test
    void nonSuccessfulResponse_exposesStatusAndServerMessage() {
        try (XNLPClient client = new XNLPClient(baseUrl())) {
            assertThatThrownBy(() -> client.getModel("missing"))
                    .isInstanceOf(XNLPClientException.class)
                    .satisfies(error -> {
                        XNLPClientException exception = (XNLPClientException) error;
                        assertThat(exception.getStatusCode()).isEqualTo(404);
                        assertThat(exception.getResponseBody()).contains("model not found");
                        assertThat(exception).hasMessageContaining("model not found");
                    });
        }
    }


    @Test
    void providerDiagnostics_decodesTypedReadinessResponse() {
        try (XNLPClient client = new XNLPClient(baseUrl())) {
            ProviderDiagnosticResponse response = client.providerDiagnostics();

            assertThat(response.checkedAt()).isEqualTo(Instant.parse("2026-09-15T09:00:00Z"));
            assertThat(response.activeProbe()).isFalse();
            assertThat(response.diagnostics()).singleElement().satisfies(diagnostic -> {
                assertThat(diagnostic.name()).isEqualTo("openai-chat");
                assertThat(diagnostic.type()).isEqualTo(ModelType.CHAT);
                assertThat(diagnostic.configured()).isTrue();
                assertThat(diagnostic.status()).isEqualTo("CONFIGURED");
            });
        }
    }

    @Test
    void probeProviderDiagnostics_usesPostWithoutRequestBody() {
        try (XNLPClient client = new XNLPClient(baseUrl())) {
            assertThat(client.probeProviderDiagnostics().activeProbe()).isTrue();
        }

        assertThat(requests).contains("POST /api/v1/models/diagnostics/probe");
    }

    @Test
    void benchmark_postsTypedRequestAndDecodesMetrics() {
        try (XNLPClient client = new XNLPClient(baseUrl())) {
            BenchmarkResult result = client.benchmark("test-model", new BenchmarkRequest(3, 2, "hello"));

            assertThat(result.getModel()).isEqualTo("test-model");
            assertThat(result.getTotalRequests()).isEqualTo(3);
            assertThat(result.getSuccessfulRequests()).isEqualTo(3);
            assertThat(result.getLatencyP95Ms()).isEqualTo(25.0);
        }

        assertThat(requests).anySatisfy(request -> {
            assertThat(request).startsWith("POST /api/v1/benchmark/test-model ");
            assertThat(request).contains("\"requests\":3", "\"concurrency\":2", "\"text\":\"hello\"");
        });
    }

    @Test
    void nonSuccessfulResponse_parsesStableErrorContract() {
        try (XNLPClient client = new XNLPClient(baseUrl())) {
            assertThatThrownBy(() -> client.getModel("stable-error"))
                    .isInstanceOf(XNLPClientException.class)
                    .satisfies(error -> {
                        XNLPClientException exception = (XNLPClientException) error;
                        assertThat(exception.getStatusCode()).isEqualTo(503);
                        assertThat(exception.getErrorCode()).isEqualTo("PROVIDER_UNAVAILABLE");
                        assertThat(exception.getRequestId()).isEqualTo("req-123");
                        assertThat(exception.getTraceId()).isEqualTo("trace-456");
                        assertThat(exception.getApiError()).isNotNull();
                    });
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getRequestBody().close();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (!body.isEmpty()) exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
