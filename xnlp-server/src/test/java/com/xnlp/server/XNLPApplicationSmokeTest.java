package com.xnlp.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("X-NLP Server Smoke Tests")
class XNLPApplicationSmokeTest {

    @LocalServerPort
    private int port;

    private final RestTemplate rest = new RestTemplate();

    {
        rest.setErrorHandler(new ResponseErrorHandler() {
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return false;
            }
            public void handleError(ClientHttpResponse response) {
                // no-op
            }
        });
    }

    @Test
    @DisplayName("aggregated health returns all probe statuses")
    @SuppressWarnings("unchecked")
    void healthEndpoint() {
        ResponseEntity<Map> resp = rest.getForEntity(
                "http://localhost:" + port + "/health", Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = resp.getBody();
        assertThat(body).containsKey("app");
        assertThat(body).containsKey("status");
        assertThat(body).containsKey("probes");
        Map<?, ?> probes = (Map<?, ?>) body.get("probes");
        assertThat(probes).containsKeys("liveness", "readiness", "startup");
    }

    @SuppressWarnings("rawtypes")
    @Test
    @DisplayName("K8s liveness probe: /livez returns 200 UP")
    void livezProbe() {
        ResponseEntity<Map> resp = rest.getForEntity(
                "http://localhost:" + port + "/livez", Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("status", "UP");
    }

    @SuppressWarnings("rawtypes")
    @Test
    @DisplayName("healthz alias also works")
    void healthzProbe() {
        ResponseEntity<Map> resp = rest.getForEntity(
                "http://localhost:" + port + "/healthz", Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("status", "UP");
    }

    @SuppressWarnings("rawtypes")
    @Test
    @DisplayName("K8s readiness probe: /readyz returns 200 READY")
    void readyzProbe() {
        ResponseEntity<Map> resp = rest.getForEntity(
                "http://localhost:" + port + "/readyz", Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("status", "READY");
    }

    @SuppressWarnings("rawtypes")
    @Test
    @DisplayName("K8s startup probe: /startupz returns 200 STARTED")
    void startupzProbe() {
        ResponseEntity<Map> resp = rest.getForEntity(
                "http://localhost:" + port + "/startupz", Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("status", "STARTED");
    }

    @Test
    @DisplayName("minimal alive: /ok returns 200 empty body")
    void okEndpoint() {
        ResponseEntity<Void> resp = rest.getForEntity(
                "http://localhost:" + port + "/ok", Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @SuppressWarnings("rawtypes")
    @Test
    @DisplayName("models endpoint lists auto-loaded models")
    void listModels() {
        ResponseEntity<List> resp = rest.getForEntity(
                "http://localhost:" + port + "/api/v1/models", List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(1);
    }

    @SuppressWarnings("rawtypes")
    @Test
    @DisplayName("predict endpoint returns text")
    void predict() {
        Map<String, String> body = Map.of("text", "Hello world");
        ResponseEntity<Map> resp = rest.postForEntity(
                "http://localhost:" + port
                        + "/api/v1/models/example-text-gen/predict",
                body, Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKeys("text", "model");
    }

    @SuppressWarnings("rawtypes")
    @Test
    @DisplayName("model not found returns 404")
    void modelNotFound() {
        Map<String, String> body = Map.of("text", "Hello");
        ResponseEntity<Map> resp = rest.postForEntity(
                "http://localhost:" + port
                        + "/api/v1/models/non-existent/predict",
                body, Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).containsKey("error");
    }

    @SuppressWarnings("rawtypes")
    @Test
    @DisplayName("benchmark endpoint works")
    void benchmark() {
        Map<String, Object> body = Map.of(
                "requests", 5,
                "concurrency", 2,
                "text", "Hello benchmark");
        ResponseEntity<Map> resp = rest.postForEntity(
                "http://localhost:" + port
                        + "/api/v1/benchmark/example-text-gen",
                body, Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKeys("model", "totalRequests", "latencyAvgMs");
    }
}
