package com.xnlp.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
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

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:xnlp-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=always",
                "spring.ai.model.chat=none",
                "spring.ai.openai.api-key=test-key"
        })
@ActiveProfiles("h2")
@DisplayName("X-NLP Server Smoke Tests")
@SuppressWarnings({"rawtypes", "unchecked"})
@Import(XNLPApplicationSmokeTest.TestChatModelConfiguration.class)
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
    void healthEndpoint() {
        ResponseEntity<Map> resp = rest.getForEntity(
                "http://localhost:" + port + "/health", Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map body = resp.getBody();
        assertThat(body).containsKey("app");
        assertThat(body).containsKey("status");
        assertThat(body).containsKey("probes");
        Map probes = (Map) body.get("probes");
        assertThat(probes).containsKeys("liveness", "readiness", "startup");
    }

    @Test
    @DisplayName("K8s liveness probe: /livez returns 200 UP")
    void livezProbe() {
        ResponseEntity<Map> resp = rest.getForEntity(
                "http://localhost:" + port + "/livez", Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("status", "UP");
    }

    @Test
    @DisplayName("healthz alias also works")
    void healthzProbe() {
        ResponseEntity<Map> resp = rest.getForEntity(
                "http://localhost:" + port + "/healthz", Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("status", "UP");
    }

    @Test
    @DisplayName("K8s readiness probe: /readyz returns 200 READY")
    void readyzProbe() {
        ResponseEntity<Map> resp = rest.getForEntity(
                "http://localhost:" + port + "/readyz", Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("status", "READY");
    }

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

    @Test
    @DisplayName("models endpoint lists auto-loaded models")
    void listModels() {
        ResponseEntity<List> resp = rest.getForEntity(
                "http://localhost:" + port + "/api/v1/models", List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).extracting("name").contains("ollama-default");
    }

    @Test
    @DisplayName("Spring AI status exposes the configured ChatModel")
    void aiStatus() {
        ResponseEntity<Map> resp = rest.getForEntity(
                "http://localhost:" + port + "/api/v1/ai/status", Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("available", true);
        assertThat((List) resp.getBody().get("models")).contains("spring-ai-default");
        assertThat(resp.getBody()).containsKeys("provider", "checkedAt");
    }

    @Test
    @DisplayName("Spring AI chat endpoint returns provider-neutral assistant output")
    void aiChat() {
        Map<String, String> body = Map.of(
                "message", "Explain how to validate a tokenizer pipeline.",
                "context", "The pipeline is evaluated with a labeled dataset.");
        ResponseEntity<Map> resp = rest.postForEntity(
                "http://localhost:" + port + "/api/v1/ai/chat", body, Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKeys("content", "model", "provider", "timestamp");
        assertThat((String) resp.getBody().get("content"))
                .startsWith("test: ")
                .contains("Explain how to validate a tokenizer pipeline.");
    }

    @Test
    @DisplayName("component-based NLP analysis returns a registered capability result")
    void nlpAnalyze() {
        Map<String, Object> body = Map.of(
                "task", "TOK",
                "text", "自然语言处理工程");
        ResponseEntity<Map> resp = rest.postForEntity(
                "http://localhost:" + port + "/api/v1/nlp/analyze", body, Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("task", "TOK");
        assertThat(resp.getBody()).containsKeys("result", "runtime");
        assertThat((Map) resp.getBody().get("result")).containsKeys("tokens", "count");
    }

    @Test
    @DisplayName("predict endpoint returns text")
    void predict() {
        Map<String, String> body = Map.of("text", "Hello world");
        ResponseEntity<Map> resp = rest.postForEntity(
                "http://localhost:" + port
                        + "/api/v1/models/ollama-default/predict",
                body, Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKeys("text", "model");
    }

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

    @Test
    @DisplayName("benchmark endpoint works")
    void benchmark() {
        Map<String, Object> body = Map.of(
                "requests", 5,
                "concurrency", 2,
                "text", "Hello benchmark");
        ResponseEntity<Map> resp = rest.postForEntity(
                "http://localhost:" + port
                        + "/api/v1/benchmark/ollama-default",
                body, Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKeys("model", "totalRequests", "latencyAvgMs");
    }

    @Test
    @DisplayName("pipeline endpoint executes registered capabilities and returns node trace")
    void pipelineTrace() {
        Map<String, Object> body = Map.of(
                "text", "这个产品很好用，我很满意。",
                "language", "zh",
                "nodes", List.of(
                        Map.of("id", "tokens", "capability", "TOK"),
                        Map.of("id", "sentiment", "capability", "SENTIMENT")));
        ResponseEntity<Map> resp = rest.postForEntity(
                "http://localhost:" + port + "/api/v1/pipelines/execute", body, Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("status", "completed");
        assertThat(resp.getBody()).containsKeys("traceId", "inputText", "outputText", "nodes");
        List nodes = (List) resp.getBody().get("nodes");
        assertThat(nodes).hasSize(2);
        assertThat((Map) nodes.get(0)).containsEntry("status", "completed");
        assertThat((Map) nodes.get(1)).extracting("capability", "status")
                .containsExactly("SENTIMENT", "completed");
    }

    @Test
    @DisplayName("construction waste workflow enforces review, gate and weighing lifecycle")
    void wasteWorkflow() {
        ResponseEntity<List> vehiclesResponse = rest.getForEntity(
                "http://localhost:" + port + "/api/v1/waste/vehicles", List.class);
        assertThat(vehiclesResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map vehicle = (Map) vehiclesResponse.getBody().getFirst();
        String vehicleId = String.valueOf(vehicle.get("id"));
        String plateNo = String.valueOf(vehicle.get("plate_no"));

        Map<String, Object> applicationPayload = new java.util.LinkedHashMap<>();
        applicationPayload.put("wasteType", "工程渣土");
        applicationPayload.put("clearReason", "基坑开挖");
        applicationPayload.put("pickupLocation", "天河区测试工地");
        applicationPayload.put("estimatedWeight", 60);
        applicationPayload.put("vehicleId", vehicleId);
        applicationPayload.put("processingSite", "广州资源化消纳场");
        applicationPayload.put("routeDescription", "工地至消纳场");
        applicationPayload.put("orderSubject", "项目");
        applicationPayload.put("subjectName", "X-NLP 测试项目");
        applicationPayload.put("contactName", "测试联系人");
        applicationPayload.put("contactPhone", "13800000000");
        applicationPayload.put("photoUrls", "/uploads/waste/a.jpg,/uploads/waste/b.jpg");

        ResponseEntity<Map> createResponse = rest.postForEntity(
                "http://localhost:" + port + "/api/v1/waste/applications", applicationPayload, Map.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String applicationId = String.valueOf(createResponse.getBody().get("id"));
        assertThat(createResponse.getBody()).containsEntry("status", "PENDING");

        ResponseEntity<Map> reviewResponse = rest.postForEntity(
                "http://localhost:" + port + "/api/v1/waste/applications/" + applicationId + "/review",
                Map.of("approve", true, "reviewer", "测试审核员"), Map.class);
        assertThat(reviewResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reviewResponse.getBody()).containsEntry("status", "APPROVED");
        String authorizationCode = String.valueOf(reviewResponse.getBody().get("code"));

        ResponseEntity<Map> gateResponse = rest.postForEntity(
                "http://localhost:" + port + "/api/v1/waste/gate/verify",
                Map.of("code", authorizationCode, "plateNo", plateNo), Map.class);
        assertThat(gateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(gateResponse.getBody()).containsEntry("allowed", true);

        ResponseEntity<Map> inboundResponse = rest.postForEntity(
                "http://localhost:" + port + "/api/v1/waste/weighings",
                Map.of("applicationId", applicationId, "eventType", "INBOUND",
                        "grossWeight", 80, "tareWeight", 20, "operatorName", "门岗一"), Map.class);
        assertThat(inboundResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(inboundResponse.getBody()).containsEntry("event_type", "INBOUND");
        assertThat(inboundResponse.getBody().get("net_weight")).isEqualTo(60.0);

        ResponseEntity<Map> outboundResponse = rest.postForEntity(
                "http://localhost:" + port + "/api/v1/waste/weighings",
                Map.of("applicationId", applicationId, "eventType", "OUTBOUND",
                        "grossWeight", 70, "tareWeight", 10, "operatorName", "门岗一"), Map.class);
        assertThat(outboundResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(outboundResponse.getBody()).containsEntry("event_type", "OUTBOUND");

        ResponseEntity<Map> completedResponse = rest.getForEntity(
                "http://localhost:" + port + "/api/v1/waste/applications/" + applicationId, Map.class);
        assertThat(completedResponse.getBody()).containsEntry("status", "COMPLETED");
        assertThat(completedResponse.getBody().get("code")).isNull();

        ResponseEntity<Map> closedGateResponse = rest.postForEntity(
                "http://localhost:" + port + "/api/v1/waste/gate/verify",
                Map.of("code", authorizationCode, "plateNo", plateNo), Map.class);
        assertThat(closedGateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(closedGateResponse.getBody()).containsEntry("allowed", false);
    }

    @Test
    @DisplayName("missing waste application returns a structured 404")
    void missingWasteApplication() {
        ResponseEntity<Map> response = rest.getForEntity(
                "http://localhost:" + port + "/api/v1/waste/applications/not-found", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("error", "resource_not_found");
    }

    @Test
    @DisplayName("evaluation endpoint queues a run and exposes progress")
    void evaluationQueuesAndCompletes() throws InterruptedException {
        Map<String, Object> dataset = Map.of(
                "name", "async-smoke",
                "description", "async evaluation smoke",
                "taskType", "SENTIMENT_ANALYSIS",
                "entries", List.of(
                        Map.of("input", "great", "expectedOutput", "positive"),
                        Map.of("input", "bad", "expectedOutput", "negative")));
        ResponseEntity<Map> datasetResp = rest.postForEntity(
                "http://localhost:" + port + "/api/v1/datasets", dataset, Map.class);
        assertThat(datasetResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String datasetId = (String) datasetResp.getBody().get("id");

        Map<String, Object> request = Map.of(
                "modelName", "ollama-default",
                "datasetId", datasetId,
                "taskType", "SENTIMENT_ANALYSIS");
        ResponseEntity<Map> runResp = rest.postForEntity(
                "http://localhost:" + port + "/api/v1/evaluations", request, Map.class);
        assertThat(runResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(runResp.getHeaders().getFirst("Location")).contains("/api/v1/evaluations/");
        String runId = (String) runResp.getBody().get("id");

        Map run = runResp.getBody();
        for (int i = 0; i < 30 && !"completed".equals(run.get("status")); i++) {
            Thread.sleep(100);
            run = rest.getForObject("http://localhost:" + port + "/api/v1/evaluations/" + runId, Map.class);
        }
        assertThat(run.get("status")).isEqualTo("completed");
        assertThat(run.get("totalEntries")).isEqualTo(2);
        assertThat(run.get("processedEntries")).isEqualTo(2);
        assertThat((Number) run.get("progressPercent")).isEqualTo(100.0);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestChatModelConfiguration {

        @Bean
        ChatModel chatModel() {
            return new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    String text = prompt.getInstructions().stream()
                            .map(message -> message.getText())
                            .reduce((left, right) -> left + "\n" + right)
                            .orElse("");
                    return new ChatResponse(List.of(
                            new Generation(new AssistantMessage("test: " + text))));
                }
            };
        }
    }

}
