package com.xnlp.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import com.xnlp.server.config.SecurityProperties;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.web.context.WebApplicationContext;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.web.FilterChainProxy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:xnlp-security-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=never",
                "spring.ai.model.chat=none",
                "spring.ai.openai.api-key=test-key",
                "xnlp.security.enabled=true",
                "xnlp.security.api-keys=integration-secret"
        })
@WebAppConfiguration
@ActiveProfiles("h2")
@DisplayName("X-NLP API key security")
@Import(XNLPApplicationSmokeTest.TestChatModelConfiguration.class)
class ApiKeySecurityIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private SecurityProperties securityProperties;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @Test
    @DisplayName("health probes remain public while application APIs require a key")
    void protectsApplicationApiButAllowsHealth() throws Exception {
        org.assertj.core.api.Assertions.assertThat(securityProperties.isEnabled()).isTrue();
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/models"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().json("{\"error\":\"unauthorized\",\"status\":401}"));

        mockMvc.perform(get("/api/v1/models").header("X-API-Key", "integration-secret"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Bearer token fallback accepts a configured API key")
    void acceptsBearerTokenFallback() throws Exception {
        mockMvc.perform(get("/api/v1/ai/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer integration-secret"))
                .andExpect(status().isOk());
    }
}
