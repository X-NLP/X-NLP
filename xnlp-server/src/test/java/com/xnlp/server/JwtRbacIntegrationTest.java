package com.xnlp.server;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.util.Base64URL;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:h2:mem:xnlp-jwt-security-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "spring.ai.model.chat=none",
        "spring.ai.openai.api-key=test-key",
        "xnlp.security.mode=JWT",
        "xnlp.security.jwt.issuer-uri=http://127.0.0.1:${test.jwk-port}",
        "xnlp.security.jwt.jwk-set-uri=http://127.0.0.1:${test.jwk-port}/jwks",
        "xnlp.security.jwt.audience=xnlp-api"
})
@ActiveProfiles("h2")
@Import(XNLPApplicationSmokeTest.TestChatModelConfiguration.class)
@DisplayName("X-NLP JWT and tenant RBAC")
class JwtRbacIntegrationTest {

    private static RSAKey rsaKey;
    private static HttpServer jwkServer;

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private FilterChainProxy filterChain;

    @BeforeAll
    static void startJwkServer() throws Exception {
        rsaKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        jwkServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwkServer.createContext("/jwks", exchange -> {
            byte[] payload = new JWKSet(rsaKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        jwkServer.start();
        System.setProperty("test.jwk-port", Integer.toString(jwkServer.getAddress().getPort()));
    }

    @AfterAll
    static void stopJwkServer() {
        System.clearProperty("test.jwk-port");
        if (jwkServer != null) jwkServer.stop(0);
    }

    @Test
    void viewer_CanReadButCannotManageTenantMemberships() throws Exception {
        MockMvc mockMvc = webAppContextSetup(context).addFilters(filterChain).build();
        String token = token("viewer-user", "tenant-a", List.of("VIEWER"), "xnlp-api",
                Instant.now().plusSeconds(300));

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"subject":"viewer-user","activeTenant":"tenant-a",
                         "roles":["VIEWER"],"credentialType":"jwt"}
                        """));
        mockMvc.perform(get("/api/v1/models").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/models/missing/predict")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"text\":\"viewer must not execute inference\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"error\":\"forbidden\",\"status\":403}"));
        mockMvc.perform(get("/api/v1/tenants/tenant-a/members")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"error\":\"forbidden\",\"status\":403}"));
    }

    @Test
    void developer_CanExecuteButCannotDeleteResources() throws Exception {
        MockMvc mockMvc = webAppContextSetup(context).addFilters(filterChain).build();
        String token = token("developer-user", "tenant-a", List.of("DEVELOPER"), "xnlp-api",
                Instant.now().plusSeconds(300));

        mockMvc.perform(post("/api/v1/models/missing/predict")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"text\":\"developer may execute inference\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/models/missing")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"error\":\"forbidden\",\"status\":403}"));
    }

    @Test
    void admin_CanReachResourceDeletion() throws Exception {
        MockMvc mockMvc = webAppContextSetup(context).addFilters(filterChain).build();
        String token = token("admin-user", "tenant-a", List.of("ADMIN"), "xnlp-api",
                Instant.now().plusSeconds(300));

        mockMvc.perform(delete("/api/v1/models/missing")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    void admin_InvalidMembershipRolesUseStableContract() throws Exception {
        MockMvc mockMvc = webAppContextSetup(context).addFilters(filterChain).build();
        String token = token("admin-user", "tenant-a", List.of("ADMIN"), "xnlp-api",
                Instant.now().plusSeconds(300));

        mockMvc.perform(put("/api/v1/tenants/tenant-a/members/new-user")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"roles\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"role_invalid\",\"status\":400}"));
        mockMvc.perform(put("/api/v1/tenants/tenant-a/members/new-user")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"roles\":[\"OWNER\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"role_invalid\",\"status\":400}"));
    }

    @Test
    void admin_CannotUseHeaderToCrossTenantBoundary() throws Exception {
        MockMvc mockMvc = webAppContextSetup(context).addFilters(filterChain).build();
        String token = token("admin-user", "tenant-a", List.of("ADMIN"), "xnlp-api",
                Instant.now().plusSeconds(300));

        mockMvc.perform(get("/api/v1/tenants/tenant-b/members")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("X-Tenant-ID", "tenant-b"))
                .andExpect(status().isForbidden());
    }

    @Test
    void wrongAudienceAndExpiredToken_AreRejected() throws Exception {
        MockMvc mockMvc = webAppContextSetup(context).addFilters(filterChain).build();
        mockMvc.perform(get("/api/v1/models").header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token("user", "tenant-a", List.of("VIEWER"), "other-api",
                                Instant.now().plusSeconds(300))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/models").header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token("user", "tenant-a", List.of("VIEWER"), "xnlp-api",
                                Instant.now().minusSeconds(120))))
                .andExpect(status().isUnauthorized());
    }

    private static String token(String subject, String tenant, List<String> roles,
                                String audience, Instant expiresAt) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("http://127.0.0.1:" + jwkServer.getAddress().getPort())
                .subject(subject)
                .audience(audience)
                .claim("tenant_id", tenant)
                .claim("roles", roles)
                .issueTime(Date.from(Instant.now().minusSeconds(5)))
                .expirationTime(Date.from(expiresAt))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(rsaKey.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(rsaKey));
        return jwt.serialize();
    }
}
