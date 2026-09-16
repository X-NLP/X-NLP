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

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(
        classes = XNLPApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:xnlp-quota-api-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
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
@DisplayName("Release 0.5 tenant quota HTTP API")
class TenantQuotaApiIntegrationTest {

    private static final String API_KEY_HEADER = "X-API-Key";

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
        jdbc.update("DELETE FROM quota_concurrency_leases");
        jdbc.update("DELETE FROM quota_usage_windows");
        jdbc.update("DELETE FROM tenant_quotas");
        ensureTenant("tenant-a", "Tenant A");
        ensureTenant("tenant-b", "Tenant B");
    }

    @Test
    @DisplayName("ADMIN can replace and read all limits with current usage")
    void quotaAdmin_UpdatesAndReadsQuota() throws Exception {
        mockMvc.perform(put("/api/v1/tenants/tenant-a/quota")
                        .header(API_KEY_HEADER, "legacy-admin-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestsPerMinute":120,
                                  "modelCallsPerMinute":30,
                                  "knowledgeImportsPerHour":8,
                                  "concurrentRequests":4
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("tenant-a"))
                .andExpect(jsonPath("$.limits.requestsPerMinute").value(120))
                .andExpect(jsonPath("$.limits.modelCallsPerMinute").value(30))
                .andExpect(jsonPath("$.limits.knowledgeImportsPerHour").value(8))
                .andExpect(jsonPath("$.limits.concurrentRequests").value(4))
                .andExpect(jsonPath("$.currentUsage.requestsInCurrentMinute").value(0))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        mockMvc.perform(get("/api/v1/tenants/tenant-a/quota")
                        .header(API_KEY_HEADER, "legacy-admin-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("tenant-a"))
                .andExpect(jsonPath("$.limits.requestsPerMinute").value(120))
                .andExpect(jsonPath("$.limits.modelCallsPerMinute").value(30))
                .andExpect(jsonPath("$.limits.knowledgeImportsPerHour").value(8))
                .andExpect(jsonPath("$.limits.concurrentRequests").value(4));
    }

    @Test
    @DisplayName("an ADMIN credential cannot read or change another tenant quota")
    void quotaAdmin_CannotCrossTenantBoundary() throws Exception {
        mockMvc.perform(get("/api/v1/tenants/tenant-b/quota")
                        .header(API_KEY_HEADER, "legacy-admin-a"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));

        mockMvc.perform(put("/api/v1/tenants/tenant-b/quota")
                        .header(API_KEY_HEADER, "legacy-admin-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validQuota()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    @DisplayName("negative and incomplete quota requests are rejected")
    void quotaAdmin_RejectsInvalidQuota() throws Exception {
        mockMvc.perform(put("/api/v1/tenants/tenant-a/quota")
                        .header(API_KEY_HEADER, "legacy-admin-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestsPerMinute":-1,
                                  "modelCallsPerMinute":30,
                                  "knowledgeImportsPerHour":8,
                                  "concurrentRequests":4
                                }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/v1/tenants/tenant-a/quota")
                        .header(API_KEY_HEADER, "legacy-admin-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestsPerMinute\":120}"))
                .andExpect(status().isBadRequest());
    }

    private static String validQuota() {
        return """
                {
                  "requestsPerMinute":120,
                  "modelCallsPerMinute":30,
                  "knowledgeImportsPerHour":8,
                  "concurrentRequests":4
                }
                """;
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
