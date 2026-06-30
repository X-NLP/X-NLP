package com.xnlp.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xnlp.core.model.BenchmarkResult;
import com.xnlp.core.model.ModelInfo;
import com.xnlp.core.model.PredictRequest;
import com.xnlp.core.model.PredictResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/**
 * Java SDK for the X-NLP serving API.
 *
 * <p>Usage:
 * <pre>{@code
 * XNLPClient client = new XNLPClient("http://localhost:8760");
 * List<ModelInfo> models = client.listModels();
 * PredictResponse resp = client.predict("example-model", "Hello world");
 * }</pre>
 */
public class XNLPClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(XNLPClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient http;

    public XNLPClient(String baseUrl) {
        this(baseUrl, HttpClient.newHttpClient());
    }

    public XNLPClient(String baseUrl, HttpClient http) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.http = http;
    }

    public Map<String, Object> health() {
        return get(baseUrl + "/api/v1/health",
                new TypeReference<Map<String, Object>>() {});
    }

    public List<ModelInfo> listModels() {
        return get(baseUrl + "/api/v1/models",
                new TypeReference<List<ModelInfo>>() {});
    }

    public ModelInfo getModel(String name) {
        return get(baseUrl + "/api/v1/models/" + name,
                new TypeReference<ModelInfo>() {});
    }

    public ModelInfo loadModel(Map<String, Object> config) {
        return post(baseUrl + "/api/v1/models", config,
                new TypeReference<ModelInfo>() {});
    }

    public void unloadModel(String name) {
        delete(baseUrl + "/api/v1/models/" + name);
    }

    public PredictResponse predict(String model, String text) {
        Map<String, Object> body = Map.of("text", text);
        return post(baseUrl + "/api/v1/models/" + model + "/predict", body,
                new TypeReference<PredictResponse>() {});
    }

    public PredictResponse predict(PredictRequest request) {
        return post(baseUrl + "/api/v1/models/" + request.getModelName()
                + "/predict", request,
                new TypeReference<PredictResponse>() {});
    }

    public BenchmarkResult benchmark(String model, int requests,
                                     int concurrency, String text) {
        Map<String, Object> body = Map.of(
                "requests", requests,
                "concurrency", concurrency,
                "text", text);
        return post(baseUrl + "/api/v1/benchmark/" + model, body,
                new TypeReference<BenchmarkResult>() {});
    }

    @Override
    public void close() {}

    private <T> T get(String url, TypeReference<T> type) {
        return request(url, "GET", null, type);
    }

    private <T> T post(String url, Object body, TypeReference<T> type) {
        return request(url, "POST", body, type);
    }

    private void delete(String url) {
        request(url, "DELETE", null, new TypeReference<Void>() {});
    }

    private <T> T request(String url, String method, Object body,
                          TypeReference<T> type) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json");
            if (body != null) {
                byte[] bytes = mapper.writeValueAsBytes(body);
                builder.header("Content-Type", "application/json")
                       .method(method, HttpRequest.BodyPublishers.ofByteArray(bytes));
            } else if ("GET".equals(method)) {
                builder.GET();
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> resp = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (type.getType().getTypeName().contains("Void")) return null;
            return mapper.readValue(resp.body(), type);
        } catch (Exception e) {
            log.error("X-NLP request failed: {} {}", method, url, e);
            throw new RuntimeException("X-NLP request failed: " + e.getMessage(), e);
        }
    }
}
