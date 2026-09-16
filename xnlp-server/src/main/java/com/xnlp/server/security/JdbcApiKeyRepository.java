package com.xnlp.server.security;

import com.xnlp.server.tenant.TenantContext;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
@Profile("!memory")
public class JdbcApiKeyRepository implements ApiKeyRepository {

    private static final String COLUMNS = """
            id, tenant_id, name, secret_prefix, secret_hash, roles, expires_at, revoked_at,
            revoke_reason, last_used_at, created_by, created_at, updated_at
            """;

    private final JdbcTemplate jdbc;

    public JdbcApiKeyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ApiKeyRecord> findActiveByHash(String secretHash, Instant now) {
        return jdbc.query("SELECT " + COLUMNS + " FROM api_keys "
                        + "WHERE secret_hash = ? AND (revoked_at IS NULL OR revoked_at > ?) "
                        + "AND (expires_at IS NULL OR expires_at > ?)",
                this::map, secretHash, Timestamp.from(now), Timestamp.from(now)).stream().findFirst();
    }

    @Override
    public Optional<ApiKeyRecord> findById(String tenantId, String id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM api_keys WHERE tenant_id = ? AND id = ?",
                this::map, TenantContext.normalize(tenantId), id).stream().findFirst();
    }

    @Override
    public List<ApiKeyRecord> findByTenant(String tenantId, int limit, int offset) {
        if (limit < 1 || limit > 200 || offset < 0) throw new IllegalArgumentException("Invalid API key pagination");
        return jdbc.query("SELECT " + COLUMNS + " FROM api_keys WHERE tenant_id = ? "
                        + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?", this::map,
                TenantContext.normalize(tenantId), limit, offset);
    }

    @Override
    public long countByTenant(String tenantId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM api_keys WHERE tenant_id = ?",
                Long.class, TenantContext.normalize(tenantId));
        return count == null ? 0L : count;
    }

    @Override
    public ApiKeyRecord create(String id, String tenantId, String name, String secretPrefix, String secretHash,
                               Set<TenantRole> roles, Instant expiresAt, String createdBy, Instant now) {
        jdbc.update("""
                INSERT INTO api_keys
                (id, tenant_id, name, secret_prefix, secret_hash, roles, expires_at, revoked_at,
                 revoke_reason, last_used_at, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?, ?, ?)
                """, id, TenantContext.normalize(tenantId), name, secretPrefix, secretHash, serializeRoles(roles),
                timestamp(expiresAt), createdBy, Timestamp.from(now), Timestamp.from(now));
        return findById(tenantId, id).orElseThrow();
    }

    @Override
    public void updateLastUsed(String id, Instant lastUsedAt) {
        jdbc.update("UPDATE api_keys SET last_used_at = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(lastUsedAt), Timestamp.from(lastUsedAt), id);
    }

    @Override
    public boolean scheduleRevocation(String tenantId, String id, String reason, Instant revokeAt, Instant updatedAt) {
        return jdbc.update("""
                UPDATE api_keys SET revoked_at = ?, revoke_reason = ?, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND (revoked_at IS NULL OR revoked_at > ?)
                """, Timestamp.from(revokeAt), reason, Timestamp.from(updatedAt),
                TenantContext.normalize(tenantId), id, Timestamp.from(revokeAt)) > 0;
    }

    @Override
    public boolean revoke(String tenantId, String id, String reason, Instant revokedAt) {
        return jdbc.update("""
                UPDATE api_keys SET revoked_at = ?, revoke_reason = ?, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revoked_at IS NULL
                """, Timestamp.from(revokedAt), reason, Timestamp.from(revokedAt),
                TenantContext.normalize(tenantId), id) > 0;
    }

    private ApiKeyRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new ApiKeyRecord(rs.getString("id"), rs.getString("tenant_id"), rs.getString("name"),
                rs.getString("secret_prefix"), rs.getString("secret_hash"), parseRoles(rs.getString("roles")),
                instant(rs, "expires_at"), instant(rs, "revoked_at"), rs.getString("revoke_reason"),
                instant(rs, "last_used_at"), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String serializeRoles(Set<TenantRole> roles) {
        if (roles == null || roles.isEmpty()) throw new IllegalArgumentException("roles must not be empty");
        return roles.stream().sorted().map(Enum::name).collect(Collectors.joining(","));
    }

    private static Set<TenantRole> parseRoles(String roles) {
        return Arrays.stream(roles.split(",")).map(TenantRole::parse)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
