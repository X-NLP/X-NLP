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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * Release 0.5 persistent Pipeline DAG HTTP contract.
 *
 * <p>The suite is disabled until the T-06 controller, service and V13 migration land. Keeping the
 * executable contract here lets those layers compile against stable DTO and JSON field names
 * without temporarily making the module build red.</p>
 */
@SpringBootTest(
        classes = XNLPApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:xnlp-pipeline-dag-api-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
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
@DisplayName("Release 0.5 Pipeline DAG HTTP API")
class PipelineDagApiIntegrationTest {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String TENANT_A_KEY = "legacy-admin-a";
    private static final String TENANT_B_KEY = "legacy-admin-b";

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
        deleteIfPresent("pipeline_run_events");
        deleteIfPresent("pipeline_run_event_sequences");
        deleteIfPresent("pipeline_node_attempts");
        deleteIfPresent("pipeline_runs");
        deleteIfPresent("pipeline_edges");
        deleteIfPresent("pipeline_nodes");
        deleteIfPresent("pipeline_definition_versions");
        deleteIfPresent("pipeline_definitions");
    }

    @Test
    @DisplayName("create and replace return a versioned DAG without accepting a body tenant")
    void pipeline_CreateAndReplace_UsesAuthenticatedTenantAndOptimisticVersion() throws Exception {
        String pipelineId = createPipeline(TENANT_A_KEY, linearPipeline("Release summarizer"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern(
                        "/api/v1/pipelines/[^/]+")))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.name").value("Release summarizer"))
                .andExpect(jsonPath("$.tenantId").doesNotExist())
                .andExpect(jsonPath("$.nodes[0].id").value("normalize"))
                .andExpect(jsonPath("$.edges[0].sourceNodeId").value("normalize"))
                .andReturn().getResponse().getHeader("Location")
                .replace("/api/v1/pipelines/", "");

        mockMvc.perform(put("/api/v1/pipelines/{id}", pipelineId)
                        .header(API_KEY_HEADER, TENANT_A_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePipeline(1, "Release summarizer v2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pipelineId))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.name").value("Release summarizer v2"));
    }

    @Test
    @DisplayName("stale replacement returns the stable pipeline version conflict contract")
    void pipeline_ReplaceWithStaleVersion_ReturnsConflict() throws Exception {
        String pipelineId = locationId(createPipeline(TENANT_A_KEY, linearPipeline("Versioned"))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location"));

        mockMvc.perform(put("/api/v1/pipelines/{id}", pipelineId)
                        .header(API_KEY_HEADER, TENANT_A_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePipeline(99, "Stale")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("pipeline_version_conflict"))
                .andExpect(jsonPath("$.detail.currentVersion").value(1));
    }

    @Test
    @DisplayName("cycle validation rejects a graph before persisting it")
    void pipeline_CreateCycle_ReturnsPipelineCycle() throws Exception {
        createPipeline(TENANT_A_KEY, cyclicPipeline())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("pipeline_cycle"))
                .andExpect(jsonPath("$.detail.nodes").isArray());
    }

    @Test
    @DisplayName("queue, read and cancel expose stable run and node-state contracts")
    void pipelineRun_QueueReadAndCancel_ExposesNodeStates() throws Exception {
        String pipelineId = locationId(createPipeline(TENANT_A_KEY, linearPipeline("Runnable"))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location"));

        String runId = mockMvc.perform(post("/api/v1/pipelines/{id}/runs", pipelineId)
                        .header(API_KEY_HEADER, TENANT_A_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(runRequest()))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern(
                        "/api/v1/pipeline-runs/[^/]+")))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.pipelineId").value(pipelineId))
                .andExpect(jsonPath("$.pipelineVersion").value(1))
                .andExpect(jsonPath("$.status").value(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is("queued"), org.hamcrest.Matchers.is("running"))))
                .andExpect(jsonPath("$.nodes[*].nodeId").isArray())
                .andReturn().getResponse().getHeader("Location")
                .replace("/api/v1/pipeline-runs/", "");

        mockMvc.perform(get("/api/v1/pipeline-runs/{runId}", runId)
                        .header(API_KEY_HEADER, TENANT_A_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(runId))
                .andExpect(jsonPath("$.nodes[0].attempt").isNumber());

        mockMvc.perform(post("/api/v1/pipeline-runs/{runId}/cancel", runId)
                        .header(API_KEY_HEADER, TENANT_A_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelRequested").value(true))
                .andExpect(jsonPath("$.status").value(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is("cancelling"), org.hamcrest.Matchers.is("cancelled"))));
    }

    @Test
    @DisplayName("run audit list is tenant scoped, filtered and paged")
    void pipelineRun_List_AppliesTenantFiltersAndStablePaging() throws Exception {
        String firstPipeline = locationId(createPipeline(TENANT_A_KEY, linearPipeline("First"))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location"));
        String secondPipeline = locationId(createPipeline(TENANT_A_KEY, linearPipeline("Second"))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location"));
        String hiddenPipeline = locationId(createPipeline(TENANT_B_KEY, linearPipeline("Hidden"))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location"));
        runId(firstPipeline);
        runId(firstPipeline);
        runId(secondPipeline);
        runId(hiddenPipeline, TENANT_B_KEY);

        mockMvc.perform(get("/api/v1/pipeline-runs")
                        .header(API_KEY_HEADER, TENANT_A_KEY)
                        .queryParam("pipelineId", firstPipeline)
                        .queryParam("page", "0")
                        .queryParam("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].pipelineId").value(firstPipeline))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.hasNext").value(true));

        mockMvc.perform(get("/api/v1/pipeline-runs")
                        .header(API_KEY_HEADER, TENANT_A_KEY)
                        .queryParam("status", "completed")
                        .queryParam("page", "0")
                        .queryParam("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[*].pipelineId",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(hiddenPipeline))));

        mockMvc.perform(get("/api/v1/pipelines/{id}", firstPipeline)
                        .header(API_KEY_HEADER, TENANT_A_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstPipeline))
                .andExpect(jsonPath("$.nodes.length()").value(2));
        mockMvc.perform(get("/api/v1/pipelines/{id}", hiddenPipeline)
                        .header(API_KEY_HEADER, TENANT_A_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("event replay and JSON trace expose ordered attempts and stable errors")
    void pipelineRun_EventsAndTrace_ExposeResumableObservabilityContracts() throws Exception {
        String pipelineId = locationId(createPipeline(TENANT_A_KEY, linearPipeline("Observable"))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location"));
        String runId = runId(pipelineId);

        mockMvc.perform(get("/api/v1/pipeline-runs/{runId}/events", runId)
                        .header(API_KEY_HEADER, TENANT_A_KEY)
                        .header("Last-Event-ID", "0")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith(
                        MediaType.TEXT_EVENT_STREAM_VALUE)));

        mockMvc.perform(get("/api/v1/pipeline-runs/{runId}/trace", runId)
                        .header(API_KEY_HEADER, TENANT_A_KEY)
                        .queryParam("format", "json"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith(
                        MediaType.APPLICATION_JSON_VALUE)))
                .andExpect(jsonPath("$.runId").value(runId))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.nodes[0].attempts[0].attempt").value(1))
                .andExpect(jsonPath("$.events[0].id").isNumber());
    }

    @Test
    @DisplayName("another tenant observes neither pipeline definitions nor run artifacts")
    void pipelineResources_AreInvisibleAcrossTenants() throws Exception {
        String pipelineId = locationId(createPipeline(TENANT_B_KEY, linearPipeline("Private"))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location"));
        String runId = runId(pipelineId, TENANT_B_KEY);

        mockMvc.perform(put("/api/v1/pipelines/{id}", pipelineId)
                        .header(API_KEY_HEADER, TENANT_A_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePipeline(1, "Leaked")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("pipeline_not_found"));

        mockMvc.perform(get("/api/v1/pipeline-runs/{runId}", runId)
                        .header(API_KEY_HEADER, TENANT_A_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("pipeline_run_not_found"));

        mockMvc.perform(post("/api/v1/pipeline-runs/{runId}/cancel", runId)
                        .header(API_KEY_HEADER, TENANT_A_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("pipeline_run_not_found"));

        mockMvc.perform(get("/api/v1/pipeline-runs/{runId}/events", runId)
                        .header(API_KEY_HEADER, TENANT_A_KEY)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/pipeline-runs/{runId}/trace", runId)
                        .header(API_KEY_HEADER, TENANT_A_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("pipeline_run_not_found"));
    }

    private org.springframework.test.web.servlet.ResultActions createPipeline(String key, String body)
            throws Exception {
        return mockMvc.perform(post("/api/v1/pipelines")
                .header(API_KEY_HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String runId(String pipelineId) throws Exception {
        return runId(pipelineId, TENANT_A_KEY);
    }

    private String runId(String pipelineId, String key) throws Exception {
        String location = mockMvc.perform(post("/api/v1/pipelines/{id}/runs", pipelineId)
                        .header(API_KEY_HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(runRequest()))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getHeader("Location");
        String runId = locationId(location);
        waitForTerminal(runId, key);
        return runId;
    }

    private void waitForTerminal(String runId, String key) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            String body = mockMvc.perform(get("/api/v1/pipeline-runs/{runId}", runId)
                            .header(API_KEY_HEADER, key))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String state = com.jayway.jsonpath.JsonPath.read(body, "$.status");
            if (java.util.Set.of("completed", "failed", "cancelled").contains(state)) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Pipeline run did not become terminal: " + runId);
    }

    private static String locationId(String location) {
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private void deleteIfPresent(String table) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) = UPPER(?)",
                Integer.class, table);
        if (count != null && count > 0) jdbc.update("DELETE FROM " + table);
    }

    private static String linearPipeline(String name) {
        return """
                {
                  "name":"%s",
                  "description":"deterministic two-node pipeline",
                  "tenantId":"tenant-b",
                  "defaults":{"timeoutSeconds":30,"retry":{"maxAttempts":2,"backoffMillis":10},"maxParallelism":2},
                  "nodes":[
                    {"id":"normalize","capability":"TEXT_NORMALIZATION","parameters":{}},
                    {"id":"summary","capability":"TEXT_SUMMARIZATION","timeoutSeconds":20,
                     "retry":{"maxAttempts":1,"backoffMillis":0},"parameters":{}}
                  ],
                  "edges":[
                    {"sourceNodeId":"normalize","targetNodeId":"summary","sourceOutput":"outputText","targetInput":"text"}
                  ]
                }
                """.formatted(name);
    }

    private static String updatePipeline(long version, String name) {
        return """
                {
                  "version":%d,
                  "name":"%s",
                  "nodes":[
                    {"id":"normalize","capability":"TEXT_NORMALIZATION","parameters":{}},
                    {"id":"summary","capability":"TEXT_SUMMARIZATION","parameters":{}}
                  ],
                  "edges":[{"sourceNodeId":"normalize","targetNodeId":"summary"}]
                }
                """.formatted(version, name);
    }

    private static String cyclicPipeline() {
        return """
                {
                  "name":"cycle",
                  "nodes":[
                    {"id":"a","capability":"TEXT_NORMALIZATION"},
                    {"id":"b","capability":"TEXT_SUMMARIZATION"}
                  ],
                  "edges":[
                    {"sourceNodeId":"a","targetNodeId":"b"},
                    {"sourceNodeId":"b","targetNodeId":"a"}
                  ]
                }
                """;
    }

    private static String runRequest() {
        return """
                {
                  "input":{"text":"Release notes need a concise summary.","language":"en"},
                  "overrides":{"summary":{"timeoutSeconds":10}},
                  "idempotencyKey":"pipeline-api-contract-run"
                }
                """;
    }
}
