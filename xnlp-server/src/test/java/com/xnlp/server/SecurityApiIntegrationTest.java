package com.xnlp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xnlp.server.config.SecurityProperties;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(
        classes = XNLPApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:xnlp-security-api-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
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
@DisplayName("Release 0.5 security HTTP API")
class SecurityApiIntegrationTest {

    private static final String API_KEY_HEADER = "X-API-Key";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private FilterChainProxy filterChain;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = webAppContextSetup(context).addFilters(filterChain).build();
        jdbc.update("DELETE FROM audit_events");
        jdbc.update("DELETE FROM api_keys");
        ensureTenant("tenant-a", "Tenant A");
        ensureTenant("tenant-b", "Tenant B");
    }

    @Test
    @DisplayName("created secret is returned once, list is redacted, and revoked key is rejected")
    void apiKeyLifecycle_ReturnsSecretOnceAndRejectsRevokedKey() throws Exception {
        JsonNode created = createKey("legacy-admin-a", "automation");
        String id = created.path("apiKey").path("id").asText();
        String secret = created.path("secret").asText();

        assertThat(secret).startsWith("xnlp_");
        assertThat(created.toString()).doesNotContain("secretHash");

        String listBody = mockMvc.perform(get("/api/v1/api-keys")
                        .header(API_KEY_HEADER, "legacy-admin-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(id))
                .andReturn().getResponse().getContentAsString();
        assertThat(listBody).doesNotContain("secretHash", secret, "\"secret\"");

        mockMvc.perform(get("/api/v1/models").header(API_KEY_HEADER, secret))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/api-keys/{id}", id)
                        .header(API_KEY_HEADER, "legacy-admin-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"credential retired\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/models").header(API_KEY_HEADER, secret))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("authentication_required"));
    }

    @Test
    @DisplayName("rotation exposes only the replacement secret and publishes the old-key deadline")
    void rotate_ReturnsReplacementSecretAndDeadline() throws Exception {
        JsonNode created = createKey("legacy-admin-a", "rotating-key");
        String id = created.path("apiKey").path("id").asText();
        String oldSecret = created.path("secret").asText();

        String responseBody = mockMvc.perform(post("/api/v1/api-keys/{id}/rotate", id)
                        .header(API_KEY_HEADER, "legacy-admin-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gracePeriodSeconds\":60}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKey.id").isNotEmpty())
                .andExpect(jsonPath("$.secret").isNotEmpty())
                .andExpect(jsonPath("$.previousKeyValidUntil").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        JsonNode rotated = objectMapper.readTree(responseBody);
        assertThat(rotated.path("secret").asText()).isNotEqualTo(oldSecret);
        assertThat(responseBody).doesNotContain("secretHash", oldSecret);
    }

    @Test
    @DisplayName("audit query and NDJSON export remain isolated to the authenticated tenant")
    void auditEndpoints_AreTenantIsolatedAndExportNdjson() throws Exception {
        createKey("legacy-admin-a", "tenant-a-key");
        createKey("legacy-admin-b", "tenant-b-key");

        mockMvc.perform(get("/api/v1/api-keys")
                        .header(API_KEY_HEADER, "legacy-admin-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].tenantId").value("tenant-b"))
                .andExpect(jsonPath("$.items[0].name").value("tenant-b-key"));

        mockMvc.perform(get("/api/v1/audit-events")
                        .header(API_KEY_HEADER, "legacy-admin-a")
                        .param("action", "api_key.created"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].tenantId").value("tenant-a"))
                .andExpect(jsonPath("$.items[0].detail.name").value("tenant-a-key"));

        String export = mockMvc.perform(get("/api/v1/audit-events/export")
                        .header(API_KEY_HEADER, "legacy-admin-a")
                        .param("action", "api_key.created"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/x-ndjson"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=security-audit.ndjson"))
                .andReturn().getResponse().getContentAsString();

        assertThat(export.lines()).hasSize(1);
        JsonNode event = objectMapper.readTree(export.lines().findFirst().orElseThrow());
        assertThat(event.path("tenantId").asText()).isEqualTo("tenant-a");
        assertThat(event.path("detail").path("name").asText()).isEqualTo("tenant-a-key");
        assertThat(export).doesNotContain("tenant-b-key", "secretHash");
    }

    private JsonNode createKey(String administratorKey, String name) throws Exception {
        String body = mockMvc.perform(post("/api/v1/api-keys")
                        .header(API_KEY_HEADER, administratorKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","roles":["VIEWER"],"expiresAt":"%s"}
                                """.formatted(name, Instant.now().plusSeconds(3600))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secret").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private void ensureTenant(String id, String displayName) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE id = ?", Integer.class, id);
        if (count != null && count == 0) {
            Instant now = Instant.now();
            jdbc.update("INSERT INTO tenants (id, display_name, created_at, updated_at) VALUES (?, ?, ?, ?)",
                    id, displayName, now, now);
        }
    }
}
