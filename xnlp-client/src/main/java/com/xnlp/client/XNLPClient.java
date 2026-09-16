package com.xnlp.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xnlp.core.eval.CompareResult;
import com.xnlp.core.eval.EvaluationDataset;
import com.xnlp.core.eval.EvaluationEntry;
import com.xnlp.core.eval.EvaluationRun;
import com.xnlp.core.model.BenchmarkResult;
import com.xnlp.core.model.ModelInfo;
import com.xnlp.core.model.PredictRequest;
import com.xnlp.core.model.PredictResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Java SDK for the X-NLP serving and evaluation API.
 *
 * <p>The client is deliberately dependency-light: it uses the JDK HTTP client
 * and Jackson, exposes typed methods for the core domain objects, and keeps
 * newer extension endpoints available as JSON maps. Requests can be scoped to
 * an API key and tenant without manually managing headers at every call.</p>
 *
 * <pre>{@code
 * try (XNLPClient client = XNLPClient.builder("http://localhost:8760")
 *         .apiKey(System.getenv("XNLP_API_KEY"))
 *         .tenantId("team-a")
 *         .build()) {
 *     PredictResponse response = client.predict("example-model", "Hello world");
 * }
 * }</pre>
 */
public class XNLPClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(XNLPClient.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> MAP_LIST = new TypeReference<>() {};
    private static final TypeReference<List<ModelInfo>> MODEL_LIST = new TypeReference<>() {};
    private static final TypeReference<List<EvaluationDataset>> DATASET_LIST = new TypeReference<>() {};
    private static final TypeReference<List<EvaluationRun>> RUN_LIST = new TypeReference<>() {};
    private static final ObjectMapper DEFAULT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Duration requestTimeout;
    private final String apiKey;
    private final String tenantId;

    /** Create a client with the default request timeout and no authentication headers. */
    public XNLPClient(String baseUrl) {
        this(builder(baseUrl));
    }

    /** Retained for embedders that provide their own JDK HTTP client. */
    public XNLPClient(String baseUrl, HttpClient http) {
        this(builder(baseUrl).httpClient(http));
    }

    /** Create a client with an API key. */
    public XNLPClient(String baseUrl, String apiKey, String tenantId) {
        this(builder(baseUrl).apiKey(apiKey).tenantId(tenantId));
    }

    private XNLPClient(Builder builder) {
        this.baseUrl = normalizeBaseUrl(builder.baseUrl);
        this.http = Objects.requireNonNull(builder.httpClient, "httpClient");
        this.mapper = Objects.requireNonNull(builder.objectMapper, "objectMapper");
        this.requestTimeout = Objects.requireNonNull(builder.requestTimeout, "requestTimeout");
        this.apiKey = blankToNull(builder.apiKey);
        this.tenantId = blankToNull(builder.tenantId);
    }

    public static Builder builder(String baseUrl) {
        return new Builder(baseUrl);
    }

    // ---------------------------------------------------------------------
    // Health and models
    // ---------------------------------------------------------------------

    public Map<String, Object> health() {
        return get("/health", MAP);
    }

    public List<ModelInfo> listModels() {
        return get("/api/v1/models", MODEL_LIST);
    }

    public List<ModelInfo> listRuntimeModels() {
        return get("/api/v1/models/runtime", MODEL_LIST);
    }

    public Map<String, Object> modelCapabilities() {
        return get("/api/v1/models/capabilities", MAP);
    }

    public ProviderDiagnosticResponse providerDiagnostics() {
        return get("/api/v1/models/diagnostics", new TypeReference<>() {});
    }

    public ProviderDiagnosticResponse probeProviderDiagnostics() {
        return post("/api/v1/models/diagnostics/probe", null, new TypeReference<>() {});
    }

    public ModelInfo getModel(String name) {
        return get(path("/api/v1/models", name), new TypeReference<>() {});
    }

    public ModelInfo loadModel(Map<String, Object> config) {
        return post("/api/v1/models", config, new TypeReference<>() {});
    }

    public ModelInfo saveModel(Map<String, Object> config) {
        return loadModel(config);
    }

    public ModelInfo activateModel(String name) {
        return post(path("/api/v1/models", name) + "/activate", null, new TypeReference<>() {});
    }

    /** Unload only the runtime; the persisted model profile remains available. */
    public void unloadModel(String name) {
        post(path("/api/v1/models", name) + "/unload", null, null);
    }

    /** Delete the persisted profile and unload its runtime, if loaded. */
    public void deleteModel(String name) {
        delete(path("/api/v1/models", name));
    }

    public Map<String, Object> testModel(String name, Map<String, Object> request) {
        return post(path("/api/v1/models", name) + "/test", request, MAP);
    }

    public PredictResponse predict(String model, String text) {
        PredictRequest request = new PredictRequest();
        request.setModelName(model);
        request.setText(text);
        return predict(request);
    }

    public PredictResponse predict(PredictRequest request) {
        String model = requireText(request.getModelName(), "modelName");
        return post(path("/api/v1/models", model) + "/predict", request, new TypeReference<>() {});
    }

    public Map<String, Object> batchPredict(String model, List<PredictRequest> requests) {
        requireText(model, "model");
        return post(path("/api/v1/models", model) + "/batch-predict",
                Map.of("requests", requests == null ? List.of() : requests), MAP);
    }

    public BenchmarkResult benchmark(String model, BenchmarkRequest request) {
        requireText(model, "model");
        Objects.requireNonNull(request, "request");
        return post(path("/api/v1/benchmark", model), request, new TypeReference<>() {});
    }

    /** Backward-compatible overload retained for existing SDK consumers. */
    public BenchmarkResult benchmark(String model, int requests, int concurrency, String text) {
        return benchmark(model, new BenchmarkRequest(requests, concurrency, text));
    }

    // ---------------------------------------------------------------------
    // Datasets
    // ---------------------------------------------------------------------

    public List<EvaluationDataset> listDatasets() {
        return get("/api/v1/datasets", DATASET_LIST);
    }

    public int countDatasets() {
        return get("/api/v1/datasets/count", MAP).get("count") instanceof Number count
                ? count.intValue() : 0;
    }

    public EvaluationDataset getDataset(String id) {
        return get(path("/api/v1/datasets", id), new TypeReference<>() {});
    }

    public EvaluationDataset createDataset(EvaluationDataset dataset) {
        return post("/api/v1/datasets", dataset, new TypeReference<>() {});
    }

    public EvaluationDataset updateDataset(String id, EvaluationDataset dataset) {
        return put(path("/api/v1/datasets", id), dataset, new TypeReference<>() {});
    }

    public void deleteDataset(String id) {
        delete(path("/api/v1/datasets", id));
    }

    public Map<String, Object> datasetEntries(String id, int page, int size) {
        return get(path("/api/v1/datasets", id) + "/entries?page=" + page + "&size=" + size, MAP);
    }

    public List<EvaluationEntry> listDatasetEntries(String id, int page, int size) {
        JsonNode entries = mapper.valueToTree(datasetEntries(id, page, size).get("entries"));
        try {
            return mapper.readerFor(new TypeReference<List<EvaluationEntry>>() {}).readValue(entries);
        } catch (IOException e) {
            throw new XNLPClientException("Unable to decode dataset entries", e);
        }
    }

    /** Returns the JSON document represented by the server's export endpoint. */
    public String exportDataset(String id) {
        return get(path("/api/v1/datasets", id) + "/export", new TypeReference<>() {});
    }

    // ---------------------------------------------------------------------
    // Evaluations and progress streaming
    // ---------------------------------------------------------------------

    public EvaluationRun startEvaluation(String modelName, String datasetId, String taskType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("modelName", modelName);
        body.put("datasetId", datasetId);
        if (taskType != null && !taskType.isBlank()) body.put("taskType", taskType);
        return post("/api/v1/evaluations", body, new TypeReference<>() {});
    }

    public List<EvaluationRun> listEvaluations() {
        return get("/api/v1/evaluations", RUN_LIST);
    }

    public List<EvaluationRun> listEvaluations(String modelName, String datasetName, String status) {
        List<String> query = new ArrayList<>();
        addQuery(query, "modelName", modelName);
        addQuery(query, "datasetName", datasetName);
        addQuery(query, "status", status);
        return get("/api/v1/evaluations" + (query.isEmpty() ? "" : "?" + String.join("&", query)), RUN_LIST);
    }

    public EvaluationRun getEvaluation(String id) {
        return get(path("/api/v1/evaluations", id), new TypeReference<>() {});
    }

    public EvaluationRun cancelEvaluation(String id) {
        return post(path("/api/v1/evaluations", id) + "/cancel", null, new TypeReference<>() {});
    }

    public CompareResult compareEvaluations(List<String> ids) {
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("ids must not be empty");
        String query = ids.stream().map(id -> "ids=" + encode(id)).reduce((a, b) -> a + "&" + b).orElseThrow();
        return get("/api/v1/evaluations/compare?" + query, new TypeReference<>() {});
    }

    /**
     * Subscribe to persisted evaluation snapshots and live SSE progress.
     * The method blocks until the server closes the stream or the callback throws.
     */
    public void streamEvaluationEvents(String id, Consumer<SseEvent> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        HttpResponse<InputStream> response = send(path("/api/v1/evaluations", id) + "/events",
                "GET", null, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            readSse(reader, consumer);
        } catch (IOException e) {
            throw new XNLPClientException("Unable to read evaluation event stream", e);
        }
    }

    // ---------------------------------------------------------------------
    // NLP, pipeline, and AI assistant extension APIs
    // ---------------------------------------------------------------------

    public List<Map<String, Object>> nlpTasks() {
        return get("/api/v1/nlp/tasks", MAP_LIST);
    }

    public Map<String, Object> analyze(Map<String, Object> request) {
        return post("/api/v1/nlp/analyze", request, MAP);
    }

    public Map<String, Object> semanticSimilarity(String text, String textPair) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("text", requireText(text, "text"));
        request.put("textPair", requireText(textPair, "textPair"));
        return post("/api/v1/nlp/semantic-similarity", request, MAP);
    }

    public Map<String, Object> semanticSearch(String datasetId, String query, int topK) {
        if (topK < 1 || topK > 100) throw new IllegalArgumentException("topK must be between 1 and 100");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("query", requireText(query, "query"));
        request.put("topK", topK);
        return post(path("/api/v1/datasets", datasetId) + "/semantic-search", request, MAP);
    }

    public Map<String, Object> nlp(String operation, Map<String, Object> request) {
        return post("/api/v1/nlp/" + requireText(operation, "operation"), request, MAP);
    }

    public List<Map<String, Object>> pipelineCapabilities() {
        return get("/api/v1/pipelines/capabilities", MAP_LIST);
    }

    public Map<String, Object> executePipeline(Map<String, Object> request) {
        return post("/api/v1/pipelines/execute", request, MAP);
    }

    public Map<String, Object> aiStatus() {
        return get("/api/v1/ai/status", MAP);
    }

    public Map<String, Object> aiChat(String message, String context, String modelName) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("message", message);
        if (context != null) request.put("context", context);
        if (modelName != null) request.put("modelName", modelName);
        return post("/api/v1/ai/chat", request, MAP);
    }

    @Override
    public void close() {
        // HttpClient owns no closeable resources in the JDK API.
    }

    // ---------------------------------------------------------------------
    // HTTP implementation
    // ---------------------------------------------------------------------

    private <T> T get(String path, TypeReference<T> type) {
        return request(path, "GET", null, type);
    }

    private <T> T post(String path, Object body, TypeReference<T> type) {
        return request(path, "POST", body, type);
    }

    private <T> T put(String path, Object body, TypeReference<T> type) {
        return request(path, "PUT", body, type);
    }

    private void delete(String path) {
        request(path, "DELETE", null, null);
    }

    private <T> T request(String path, String method, Object body, TypeReference<T> type) {
        HttpResponse<String> response = send(path, method, body, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (type == null || response.body() == null || response.body().isBlank()) return null;
        try {
            return mapper.readValue(response.body(), type);
        } catch (JsonProcessingException e) {
            throw new XNLPClientException("Unable to decode X-NLP response for " + method + " " + path, e);
        }
    }

    private <T> HttpResponse<T> send(String path, String method, Object body,
                                     HttpResponse.BodyHandler<T> handler) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(requestTimeout)
                    .header("Accept", "application/json");
            if (apiKey != null) request.header("X-API-Key", apiKey);
            if (tenantId != null) request.header("X-Tenant-ID", tenantId);
            if (body != null) {
                request.header("Content-Type", "application/json");
                request.method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            } else if ("GET".equals(method)) {
                request.GET();
            } else {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<T> response = http.send(request.build(), handler);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String responseBody = response.body() instanceof String text ? text : "";
                throw new XNLPClientException(errorMessage(response.statusCode(), responseBody),
                        response.statusCode(), responseBody, parseApiError(responseBody));
            }
            return response;
        } catch (XNLPClientException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new XNLPClientException("X-NLP request interrupted: " + method + " " + path, e);
        } catch (IOException | IllegalArgumentException e) {
            log.debug("X-NLP request failed: {} {}", method, path, e);
            throw new XNLPClientException("X-NLP request failed: " + method + " " + path + ": " + e.getMessage(), e);
        }
    }

    private ApiErrorResponse parseApiError(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            return mapper.readValue(body, ApiErrorResponse.class);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private String errorMessage(int status, String body) {
        try {
            JsonNode node = mapper.readTree(body);
            for (String field : List.of("message", "error", "detail")) {
                JsonNode value = node.get(field);
                if (value != null && !value.isNull() && !value.asText().isBlank()) return value.asText();
            }
        } catch (JsonProcessingException ignored) {
            // Preserve the raw body below when an upstream returns plain text.
        }
        return "X-NLP server returned HTTP " + status + (body == null || body.isBlank() ? "" : ": " + body);
    }

    private void readSse(BufferedReader reader, Consumer<SseEvent> consumer) throws IOException {
        String event = "message";
        String id = null;
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    consumer.accept(new SseEvent(id, event, data.toString()));
                    data.setLength(0);
                }
                event = "message";
                id = null;
                continue;
            }
            if (line.startsWith(":")) continue;
            int separator = line.indexOf(':');
            String field = separator < 0 ? line : line.substring(0, separator);
            String value = separator < 0 ? "" : line.substring(separator + 1).stripLeading();
            switch (field) {
                case "event" -> event = value;
                case "id" -> id = value;
                case "data" -> {
                    if (data.length() > 0) data.append('\n');
                    data.append(value);
                }
                default -> { }
            }
        }
        if (data.length() > 0) consumer.accept(new SseEvent(id, event, data.toString()));
    }

    private static String path(String prefix, String value) {
        return prefix + "/" + encode(requireText(value, "path parameter"));
    }

    private static void addQuery(List<String> query, String name, String value) {
        if (value != null && !value.isBlank()) query.add(encode(name) + "=" + encode(value));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = requireText(value, "baseUrl").trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record SseEvent(String id, String event, String data) {
    }

    public static final class Builder {
        private final String baseUrl;
        private HttpClient httpClient = HttpClient.newHttpClient();
        private ObjectMapper objectMapper = DEFAULT_MAPPER;
        private Duration requestTimeout = Duration.ofSeconds(60);
        private String apiKey;
        private String tenantId;

        private Builder(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
            return this;
        }

        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
                throw new IllegalArgumentException("requestTimeout must be positive");
            }
            this.requestTimeout = requestTimeout;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public XNLPClient build() {
            return new XNLPClient(this);
        }
    }
}
