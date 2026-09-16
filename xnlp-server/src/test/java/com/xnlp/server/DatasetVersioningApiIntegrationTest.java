package com.xnlp.server;

import com.xnlp.server.dataset.versioning.DatasetMetadata;
import com.xnlp.server.dataset.versioning.DatasetVersionRepository;
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

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(
        classes = XNLPApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:xnlp-dataset-version-api-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
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
@DisplayName("Release 0.5 Dataset versioning HTTP API")
class DatasetVersioningApiIntegrationTest {

    private static final String API_KEY_HEADER = "X-API-Key";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private FilterChainProxy filterChain;

    @Autowired
    private DatasetVersionRepository datasets;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = webAppContextSetup(context).addFilters(filterChain).build();
        jdbc.update("DELETE FROM dataset_import_errors");
        jdbc.update("DELETE FROM dataset_import_jobs");
        jdbc.update("DELETE FROM dataset_version_snapshots");
        jdbc.update("DELETE FROM versioned_dataset_entries");
        jdbc.update("DELETE FROM versioned_datasets");
        datasets.createDataset(
                "tenant-a", "quality", new DatasetMetadata("Quality", "Regression data", "TEXT_CLASSIFICATION"),
                Instant.now());
        datasets.createDataset(
                "tenant-b", "quality", new DatasetMetadata("Other", null, null), Instant.now());
    }

    @Test
    @DisplayName("entry CRUD creates immutable versions and rejects stale writes")
    void entries_CreateReplaceDeleteAndRejectVersionConflicts() throws Exception {
        mockMvc.perform(post("/api/v1/datasets/quality/entries")
                        .header(API_KEY_HEADER, "legacy-admin-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(entry("entry-1", 0, "first", 0)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("entry-1"))
                .andExpect(jsonPath("$.datasetVersion").value(1));

        mockMvc.perform(post("/api/v1/datasets/quality/entries")
                        .header(API_KEY_HEADER, "legacy-admin-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(entry("stale", 1, "stale", 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("dataset_version_conflict"))
                .andExpect(jsonPath("$.detail.currentVersion").value(1));

        mockMvc.perform(put("/api/v1/datasets/quality/entries/entry-1")
                        .header(API_KEY_HEADER, "legacy-admin-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(entry(null, 2, "updated", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.input").value("updated"))
                .andExpect(jsonPath("$.datasetVersion").value(2));

        mockMvc.perform(get("/api/v1/datasets/quality/versions")
                        .header(API_KEY_HEADER, "legacy-admin-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].version").value(2))
                .andExpect(jsonPath("$.items[1].version").value(1));

        mockMvc.perform(delete("/api/v1/datasets/quality/entries/entry-1")
                        .queryParam("expectedVersion", "2")
                        .header(API_KEY_HEADER, "legacy-admin-a"))
                .andExpect(status().isNoContent());

        mockMvc.perform(put("/api/v1/datasets/quality/entries/missing")
                        .header(API_KEY_HEADER, "legacy-admin-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(entry(null, 0, "missing", 3)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("dataset_entry_not_found"));
    }

    @Test
    @DisplayName("Dataset resources and import reports are isolated by authenticated tenant")
    void datasets_HideCrossTenantResources() throws Exception {
        mockMvc.perform(post("/api/v1/datasets/quality/entries")
                        .header(API_KEY_HEADER, "legacy-admin-a")
                        .header("X-Tenant-ID", "tenant-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(entry("entry-a", 0, "tenant-a", 0)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value("tenant-a"));

        mockMvc.perform(get("/api/v1/datasets/quality/versions")
                        .header(API_KEY_HEADER, "legacy-admin-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        mockMvc.perform(get("/api/v1/datasets/missing/versions")
                        .header(API_KEY_HEADER, "legacy-admin-a"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("dataset_not_found"));
    }

    @Test
    @DisplayName("JSON import returns an accepted job and locates invalid rows")
    void import_ReturnsJobAndValidationReport() throws Exception {
        String response = mockMvc.perform(post("/api/v1/datasets/quality/imports")
                        .header(API_KEY_HEADER, "legacy-admin-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceName":"quality.json",
                                  "expectedVersion":0,
                                  "entries":[
                                    {"id":"valid","sequence":0,"input":"hello","expectedOutput":"world"},
                                    {"id":"invalid","sequence":-1,"input":"bad"},
                                    {"id":"blank","sequence":2,"input":" "}
                                  ]
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.job.status").value("COMPLETED_WITH_ERRORS"))
                .andExpect(jsonPath("$.job.totalRows").value(3))
                .andExpect(jsonPath("$.job.importedRows").value(1))
                .andExpect(jsonPath("$.job.failedRows").value(2))
                .andExpect(jsonPath("$.job.resultingVersion").value(1))
                .andExpect(jsonPath("$.errors[0].rowNumber").value(2))
                .andExpect(jsonPath("$.errors[0].errorCode").value("dataset_import_invalid"))
                .andExpect(jsonPath("$.errors[1].rowNumber").value(3))
                .andReturn().getResponse().getContentAsString();

        String jobId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("job").path("id").asText();
        mockMvc.perform(get("/api/v1/datasets/quality/imports/{jobId}", jobId)
                        .header(API_KEY_HEADER, "legacy-admin-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.id").value(jobId))
                .andExpect(jsonPath("$.errors.length()").value(2));

        mockMvc.perform(get("/api/v1/datasets/quality/imports/{jobId}", jobId)
                        .header(API_KEY_HEADER, "legacy-admin-b"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("dataset_import_not_found"));
    }

    private static String entry(String id, int sequence, String input, long expectedVersion) {
        String idField = id == null ? "" : "\"id\":\"" + id + "\",";
        return "{" + idField
                + "\"expectedVersion\":" + expectedVersion + ','
                + "\"sequence\":" + sequence + ','
                + "\"input\":\"" + input + "\","
                + "\"expectedOutput\":\"expected\","
                + "\"labels\":{\"label\":\"ok\"},"
                + "\"metadata\":{\"source\":\"test\"}}";
    }
}
