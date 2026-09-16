package com.xnlp.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Timestamp;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(
        classes = XNLPApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:xnlp-resumable-evaluation-api-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=never",
                "spring.ai.model.chat=none",
                "spring.ai.model.embedding=none",
                "spring.ai.openai.api-key=test-key",
                "xnlp.security.mode=API_KEY",
                "xnlp.security.api-key-tenants.tenant-a=legacy-admin-a",
                "xnlp.security.api-key-tenants.tenant-b=legacy-admin-b"
        })
@ActiveProfiles("h2")
@Import(XNLPApplicationSmokeTest.TestChatModelConfiguration.class)
@DisplayName("Release 0.5 resumable evaluation HTTP API")
class ResumableEvaluationApiIntegrationTest {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String TENANT_A_KEY = "legacy-admin-a";
    private static final String TENANT_B_KEY = "legacy-admin-b";
    private static final String RUN_ID = "run-source";
    private static final String FAILED_SAMPLE_ID = "sample-failed";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private FilterChainProxy filterChain;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = webAppContextSetup(context).addFilters(filterChain).build();
        jdbc.update("DELETE FROM evaluation_sample_results");
        jdbc.update("DELETE FROM evaluation_checkpoints");
        jdbc.update("DELETE FROM evaluation_recovery_leases");
        jdbc.update("DELETE FROM evaluation_recovery_runs");
        jdbc.update("DELETE FROM evaluation_runs");
        jdbc.update("DELETE FROM dataset_version_snapshots WHERE dataset_id = 'dataset-1'");
        jdbc.update("DELETE FROM versioned_dataset_entries WHERE dataset_id = 'dataset-1'");
        jdbc.update("DELETE FROM versioned_datasets WHERE id = 'dataset-1'");
        ensureTenant("tenant-a");
        ensureTenant("tenant-b");
        insertVersionedDataset();
        insertRun("tenant-a", RUN_ID, "failed");
        insertSample("tenant-a", FAILED_SAMPLE_ID, RUN_ID, 0, "failed", null, "provider_timeout");
        insertSample("tenant-a", "sample-completed", RUN_ID, 1, "completed", "positive", null);
        insertSample("tenant-a", "sample-failed-2", RUN_ID, 2, "failed", null, "provider_unavailable");
    }

    @Test
    @DisplayName("sample results support status filtering and stable zero-based paging")
    void samples_FilterAndPageWithoutLeakingOtherTenants() throws Exception {
        mockMvc.perform(get("/api/v1/evaluations/{id}/samples", RUN_ID)
                        .header(API_KEY_HEADER, TENANT_A_KEY)
                        .queryParam("status", "failed")
                        .queryParam("page", "0")
                        .queryParam("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].runId").value(RUN_ID))
                .andExpect(jsonPath("$.items[0].status").value("failed"))
                .andExpect(jsonPath("$.items[0].errorCode").isNotEmpty());

        mockMvc.perform(get("/api/v1/evaluations/{id}/samples", RUN_ID)
                        .header(API_KEY_HEADER, TENANT_A_KEY)
                        .queryParam("status", "failed")
                        .queryParam("page", "1")
                        .queryParam("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("missing and cross-tenant evaluation IDs share the not-found contract")
    void samples_HideExistenceAcrossTenants() throws Exception {
        mockMvc.perform(get("/api/v1/evaluations/{id}/samples", "missing")
                        .header(API_KEY_HEADER, TENANT_A_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("evaluation_not_found"));

        insertRun("tenant-b", "tenant-b-only", "failed");
        mockMvc.perform(get("/api/v1/evaluations/{id}/samples", "tenant-b-only")
                        .header(API_KEY_HEADER, TENANT_A_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("evaluation_not_found"));
    }

    @Test
    @DisplayName("failed-only retry queues a child run with source and root lineage")
    void retryFailedOnly_CreatesLineageForFailedSamples() throws Exception {
        mockMvc.perform(post("/api/v1/evaluations/{id}/retry", RUN_ID)
                        .header(API_KEY_HEADER, TENANT_A_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"failedOnly\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern(
                        "/api/v1/evaluations/[^/]+")))
                .andExpect(jsonPath("$.sourceRunId").value(RUN_ID))
                .andExpect(jsonPath("$.rootRunId").value(RUN_ID))
                .andExpect(jsonPath("$.failedOnly").value(true))
                .andExpect(jsonPath("$.selectedSamples").value(2))
                .andExpect(jsonPath("$.run.id").isNotEmpty())
                .andExpect(jsonPath("$.run.status").value("queued"));
    }

    @Test
    @DisplayName("retry rejects missing, cross-tenant and non-terminal runs")
    void retry_UsesStableNotFoundAndNotRetryableErrors() throws Exception {
        mockMvc.perform(post("/api/v1/evaluations/{id}/retry", "missing")
                        .header(API_KEY_HEADER, TENANT_A_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"failedOnly\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("evaluation_not_found"));

        insertRun("tenant-b", "tenant-b-only", "failed");
        mockMvc.perform(post("/api/v1/evaluations/{id}/retry", "tenant-b-only")
                        .header(API_KEY_HEADER, TENANT_A_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"failedOnly\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("evaluation_not_found"));

        insertRun("tenant-a", "run-running", "running");
        mockMvc.perform(post("/api/v1/evaluations/{id}/retry", "run-running")
                        .header(API_KEY_HEADER, TENANT_A_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"failedOnly\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("evaluation_not_retryable"));
    }

    private void insertVersionedDataset() {
        Instant now = Instant.parse("2026-09-16T08:00:00Z");
        jdbc.update("""
                INSERT INTO versioned_datasets (
                    tenant_id, id, name, task_type, version, entry_count, created_at, updated_at)
                VALUES ('tenant-a', 'dataset-1', 'Quality', 'SENTIMENT_ANALYSIS', 0, 3, ?, ?)
                """, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO dataset_version_snapshots (
                    tenant_id, dataset_id, version, name, task_type, entry_count,
                    entries_json, created_by, created_at)
                VALUES ('tenant-a', 'dataset-1', 0, 'Quality', 'SENTIMENT_ANALYSIS', 3, ?, 'fixture', ?)
                """, """
                [
                  {"id":"sample-failed","sequence":0,"input":"input-0","expectedOutput":"positive","labels":{},"metadata":{}},
                  {"id":"sample-completed","sequence":1,"input":"input-1","expectedOutput":"positive","labels":{},"metadata":{}},
                  {"id":"sample-failed-2","sequence":2,"input":"input-2","expectedOutput":"positive","labels":{},"metadata":{}}
                ]
                """, Timestamp.from(now));
    }

    private void ensureTenant(String tenantId) {
        Instant now = Instant.parse("2026-09-16T08:00:00Z");
        jdbc.update("""
                INSERT INTO tenants (id, display_name, created_at, updated_at)
                SELECT ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM tenants WHERE id = ?)
                """, tenantId, tenantId, Timestamp.from(now), Timestamp.from(now), tenantId);
    }

    private void insertRun(String tenantId, String id, String status) {
        Instant now = Instant.parse("2026-09-16T08:00:00Z");
        jdbc.update("""
                INSERT INTO evaluation_runs (
                    id, tenant_id, model_name, dataset_id, dataset_name, task_type, status,
                    created_at, completed_at, elapsed_seconds, total_entries, processed_entries,
                    progress_percent, cancel_requested, dataset_version, parent_run_id, root_run_id,
                    attempt_no, retry_failed_only)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, tenantId, "spring-ai-default", "dataset-1", "Quality", "SENTIMENT_ANALYSIS", status,
                Timestamp.from(now), "running".equals(status) ? null : Timestamp.from(now.plusSeconds(5)),
                5.0, 3, "running".equals(status) ? 1 : 3, "running".equals(status) ? 33.3 : 100.0, false,
                0, null, id, 0, false);
        jdbc.update("""
                INSERT INTO evaluation_recovery_runs (
                    tenant_id, run_id, dataset_id, dataset_version, status, root_run_id, parent_run_id,
                    attempt_no, retry_failed_only, total_samples, actor_id, created_at, updated_at, completed_at)
                VALUES (?, ?, ?, 0, ?, ?, NULL, 0, FALSE, 3, ?, ?, ?, ?)
                """, tenantId, id, "dataset-1", status.toUpperCase(), id, "fixture",
                Timestamp.from(now), Timestamp.from(now),
                "running".equals(status) ? null : Timestamp.from(now.plusSeconds(5)));
        jdbc.update("""
                INSERT INTO evaluation_checkpoints (
                    tenant_id, run_id, next_sample_sequence, processed_samples, succeeded_samples,
                    failed_samples, fencing_token, updated_at)
                VALUES (?, ?, 0, 0, 0, 0, 0, ?)
                """, tenantId, id, Timestamp.from(now));
        jdbc.update("""
                INSERT INTO evaluation_recovery_leases (tenant_id, run_id, fencing_token)
                VALUES (?, ?, 0)
                """, tenantId, id);
    }

    private void insertSample(String tenantId, String id, String runId, int sequence,
                              String status, String actualOutput, String errorCode) {
        Instant startedAt = Instant.parse("2026-09-16T08:00:00Z").plusSeconds(sequence);
        jdbc.update("""
                INSERT INTO evaluation_sample_results (
                    tenant_id, run_id, sample_id, sample_sequence, status, expected_output,
                    actual_output, score_json, error_code, error_message, idempotency_key, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, runId, id, sequence,
                "failed".equals(status) ? "FAILED" : "SUCCEEDED", "positive", actualOutput,
                actualOutput == null ? null : "{\"accuracy\":1.0}", errorCode,
                errorCode == null ? null : "sample failed", runId + ':' + id,
                Timestamp.from(startedAt.plusMillis(250)));
    }
}
