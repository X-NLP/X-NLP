package com.xnlp.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Portable JDBC API key and audit repositories")
class JdbcApiKeyAuditRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-16T08:00:00Z");

    private JdbcApiKeyRepository keys;
    private JdbcAuditEventRepository audit;
    private JdbcTemplate jdbc;
    private TenantAuthorizationService authorization;

    @BeforeEach
    void clearDataAndAuthenticate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:xnlp-api-key-audit-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE tenants (id VARCHAR(64) PRIMARY KEY, display_name VARCHAR(190) NOT NULL, "
                + "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE api_keys (id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, "
                + "name VARCHAR(190) NOT NULL, secret_prefix VARCHAR(32) NOT NULL, secret_hash VARCHAR(64) NOT NULL, "
                + "roles VARCHAR(255) NOT NULL, expires_at TIMESTAMP, revoked_at TIMESTAMP, "
                + "revoke_reason VARCHAR(255), last_used_at TIMESTAMP, created_by VARCHAR(190) NOT NULL, "
                + "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE audit_events (id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, "
                + "actor VARCHAR(190) NOT NULL, action VARCHAR(96) NOT NULL, resource_type VARCHAR(96) NOT NULL, "
                + "resource_id VARCHAR(190), outcome VARCHAR(32) NOT NULL, detail TEXT, "
                + "occurred_at TIMESTAMP NOT NULL, retain_until TIMESTAMP)");
        jdbc.update("INSERT INTO tenants (id, display_name, created_at, updated_at) VALUES (?, ?, ?, ?)",
                "default", "Default", java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        keys = new JdbcApiKeyRepository(jdbc);
        audit = new JdbcAuditEventRepository(jdbc, new ObjectMapper());
        authorization = new TenantAuthorizationService(new InMemoryTenantMembershipRepository());
        authenticate(new XnlpPrincipal("admin", "default", Set.of(TenantRole.ADMIN), "jwt"));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void create_PersistsOnlyHashAndSupportsActiveHashLookup() {
        ApiKeyService service = new ApiKeyService(
                keys, audit, authorization, Clock.fixed(NOW, ZoneOffset.UTC));

        ApiKeyService.CreatedApiKey created = service.create(
                "automation", Set.of(TenantRole.DEVELOPER, TenantRole.VIEWER), NOW.plusSeconds(3600));

        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT secret_prefix, secret_hash, roles FROM api_keys WHERE id = ?", created.record().id());
        assertThat(stored.get("SECRET_HASH")).isEqualTo(ApiKeyService.hash(created.secret()));
        assertThat(stored.get("SECRET_PREFIX")).isEqualTo(created.secret().substring(0, 12));
        assertThat(stored.get("ROLES").toString()).contains("DEVELOPER", "VIEWER");
        assertThat(stored.values()).noneMatch(created.secret()::equals);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM api_keys WHERE secret_hash = ? OR secret_prefix = ? OR roles = ?",
                Integer.class, created.secret(), created.secret(), created.secret())).isZero();

        assertThat(keys.findActiveByHash(ApiKeyService.hash(created.secret()), NOW))
                .hasValueSatisfying(record -> {
                    assertThat(record.id()).isEqualTo(created.record().id());
                    assertThat(record.roles()).containsExactlyInAnyOrder(TenantRole.DEVELOPER, TenantRole.VIEWER);
                });
        assertThat(keys.findActiveByHash(ApiKeyService.hash("wrong-secret"), NOW)).isEmpty();
    }

    @Test
    void activeLookup_RejectsExpiredAndRevokedKeys_AndLastUsedIsUpdated() {
        ApiKeyRecord active = keys.create("active", "default", "active", "xnlp_active",
                ApiKeyService.hash("active-secret"), Set.of(TenantRole.VIEWER), NOW.plusSeconds(60), "admin", NOW);
        keys.create("expired", "default", "expired", "xnlp_expire",
                ApiKeyService.hash("expired-secret"), Set.of(TenantRole.VIEWER), NOW, "admin", NOW.minusSeconds(60));
        keys.create("revoked", "default", "revoked", "xnlp_revoke",
                ApiKeyService.hash("revoked-secret"), Set.of(TenantRole.VIEWER), NOW.plusSeconds(60), "admin", NOW);
        assertThat(keys.revoke("default", "revoked", "compromised", NOW.minusSeconds(1))).isTrue();

        assertThat(keys.findActiveByHash(ApiKeyService.hash("active-secret"), NOW)).isPresent();
        assertThat(keys.findActiveByHash(ApiKeyService.hash("expired-secret"), NOW)).isEmpty();
        assertThat(keys.findActiveByHash(ApiKeyService.hash("revoked-secret"), NOW)).isEmpty();

        Instant usedAt = NOW.plusSeconds(5);
        keys.updateLastUsed(active.id(), usedAt);
        assertThat(keys.findById("default", active.id()))
                .hasValueSatisfying(record -> {
                    assertThat(record.lastUsedAt()).isEqualTo(usedAt);
                    assertThat(record.updatedAt()).isEqualTo(usedAt);
                });
    }

    @Test
    void auditQueries_AreTenantIsolatedAndApplyAllFilters() {
        jdbc.update("INSERT INTO tenants (id, display_name, created_at, updated_at) VALUES (?, ?, ?, ?)",
                "tenant-b", "Tenant B", java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        audit.append("default", "alice", "api_key.created", "api_key", "key-1", "success",
                Map.of("name", "one"), NOW.minusSeconds(20), null);
        audit.append("default", "bob", "api_key.revoked", "api_key", "key-2", "success",
                Map.of("reason", "rotation"), NOW.minusSeconds(10), NOW.plusSeconds(86400));
        audit.append("default", "alice", "member.updated", "membership", "member-1", "success",
                Map.of(), NOW, null);
        audit.append("tenant-b", "alice", "api_key.created", "api_key", "key-other", "success",
                Map.of("name", "other"), NOW.minusSeconds(15), null);

        assertThat(audit.find("default", null, null, null, null, null, 20, 0))
                .extracting(AuditEvent::resourceId)
                .containsExactly("member-1", "key-2", "key-1")
                .doesNotContain("key-other");
        assertThat(audit.find("default", "alice", "api_key.created", "api_key",
                NOW.minusSeconds(30), NOW.minusSeconds(15), 20, 0))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.resourceId()).isEqualTo("key-1");
                    assertThat(event.detail()).containsEntry("name", "one");
                });
        assertThat(audit.count("default", null, null, "api_key",
                NOW.minusSeconds(15), NOW)).isEqualTo(1);
        assertThat(audit.count("tenant-b", null, null, null, null, null)).isEqualTo(1);
    }

    private void authenticate(XnlpPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null,
                        principal.roles().stream()
                                .map(role -> new SimpleGrantedAuthority(role.authority())).toList()));
    }
}
