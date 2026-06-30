package com.xnlp.server.controller;

import com.xnlp.server.service.ModelService;
import com.xnlp.server.startup.ModelInitializer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health and Kubernetes probe endpoints.
 *
 * <h3>K8s probe conventions</h3>
 * <table>
 *   <tr><td>{@code /healthz} ({@code /livez})</td><td>Liveness — is the process alive? Restart if failing.</td></tr>
 *   <tr><td>{@code /readyz}</td><td>Readiness — can this pod serve traffic? Remove from LB if failing.</td></tr>
 *   <tr><td>{@code /startupz}</td><td>Startup — has initialisation completed? K8s waits before invoking liveness.</td></tr>
 *   <tr><td>{@code /health}</td><td>Aggregated health detail for humans and dashboards.</td></tr>
 *   <tr><td>{@code /ok}</td><td>Absolute-minimal alive check (200 with no body).</td></tr>
 * </table>
 */
@RestController
public class HealthController {

    private final ModelService modelService;
    private final ModelInitializer modelInitializer;

    public HealthController(ModelService modelService, ModelInitializer modelInitializer) {
        this.modelService = modelService;
        this.modelInitializer = modelInitializer;
    }

    // ---- K8s standard probes ------------------------------------------------

    /**
     * Liveness probe — answers "is the process alive?"
     *
     * <p>Returns 200 as long as the JVM and Spring context are running.
     * Deliberately does <em>not</em> check any external dependency,
     * so a transient downstream failure does not trigger a restart storm.
     * Also available at {@code /healthz}.
     */
    @GetMapping(value = {"/livez", "/healthz"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> livez() {
        return Map.of("status", "UP", "timestamp", Instant.now().toString());
    }

    /**
     * Readiness probe — answers "can this instance serve requests?"
     *
     * <p>Checks that the model registry is initialised. If the registry is
     * present the pod is considered ready for traffic.
     */
    @GetMapping(value = "/readyz", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> readyz() {
        try {
            boolean ready = modelService.getRegistry() != null;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", ready ? "READY" : "NOT_READY");
            body.put("loaded_models", modelService.listModels().size());
            body.put("timestamp", Instant.now().toString());
            return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "NOT_READY", "reason", e.getMessage()));
        }
    }

    /**
     * Startup probe — answers "has initialisation finished?"
     *
     * <p>Use for slow-starting workloads where model loading takes tens of seconds.
     * K8s 1.20+ will not invoke the liveness probe until this returns 200,
     * preventing premature pod restarts during long initialisation.
     */
    @GetMapping(value = "/startupz", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> startupz() {
        boolean done = modelInitializer.isStartupComplete();
        Map<String, Object> body = Map.of(
                "status", done ? "STARTED" : "STARTING",
                "startup_complete", done,
                "timestamp", Instant.now().toString()
        );
        return ResponseEntity.status(done ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    // ---- Human-friendly endpoints -------------------------------------------

    /** Aggregated health — all probe results in one response for humans and dashboards. */
    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("app", "xnlp-server");
        body.put("status", "UP");
        body.put("timestamp", Instant.now().toString());

        Map<String, Object> probes = new LinkedHashMap<>();
        probes.put("liveness", Map.of("status", "UP"));
        probes.put("readiness", modelService.getRegistry() != null
                ? Map.of("status", "READY", "loaded_models", modelService.listModels().size())
                : Map.of("status", "NOT_READY"));
        probes.put("startup", Map.of(
                "status", modelInitializer.isStartupComplete() ? "STARTED" : "STARTING",
                "startup_complete", modelInitializer.isStartupComplete()
        ));
        body.put("probes", probes);
        return body;
    }

    /** Absolute-minimal alive signal — returns 200 with an empty body. */
    @GetMapping(value = "/ok", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<Void> ok() {
        return ResponseEntity.ok().build();
    }
}
