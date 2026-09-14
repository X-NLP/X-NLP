package com.xnlp.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xnlp.core.model.ModelInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
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
