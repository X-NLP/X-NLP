package com.xnlp.server.security;

import com.xnlp.server.tenant.TenantContext;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
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
public class JdbcTenantMembershipRepository implements TenantMembershipRepository {

    private final JdbcTemplate jdbc;

    public JdbcTenantMembershipRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TenantMembership> find(String tenantId, String subject) {
        List<TenantMembership> results = jdbc.query("""
                SELECT tenant_id, subject, roles, created_at, updated_at
                FROM tenant_memberships WHERE tenant_id = ? AND subject = ?
                """, this::map, TenantContext.normalize(tenantId), normalizeSubject(subject));
        return results.stream().findFirst();
    }

    @Override
    public List<TenantMembership> findByTenant(String tenantId, int limit, int offset) {
        if (limit < 1 || limit > 1000 || offset < 0) {
            throw new IllegalArgumentException("Invalid membership pagination");
        }
        return jdbc.query("""
                SELECT tenant_id, subject, roles, created_at, updated_at
                FROM tenant_memberships WHERE tenant_id = ?
                ORDER BY subject LIMIT ? OFFSET ?
                """, this::map, TenantContext.normalize(tenantId), limit, offset);
    }

    @Override
    public TenantMembership save(String tenantId, String subject, Set<TenantRole> roles) {
        String normalizedTenant = TenantContext.normalize(tenantId);
        String normalizedSubject = normalizeSubject(subject);
        String serializedRoles = serializeRoles(roles);
        Instant now = Instant.now();
        int updated = jdbc.update("""
                UPDATE tenant_memberships SET roles = ?, updated_at = ?
                WHERE tenant_id = ? AND subject = ?
                """, serializedRoles, Timestamp.from(now), normalizedTenant, normalizedSubject);
        if (updated == 0) {
            ensureTenant(normalizedTenant, now);
            try {
                jdbc.update("""
                        INSERT INTO tenant_memberships (tenant_id, subject, roles, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?)
                        """, normalizedTenant, normalizedSubject, serializedRoles,
                        Timestamp.from(now), Timestamp.from(now));
            } catch (DuplicateKeyException ignored) {
                jdbc.update("""
                        UPDATE tenant_memberships SET roles = ?, updated_at = ?
                        WHERE tenant_id = ? AND subject = ?
                        """, serializedRoles, Timestamp.from(now), normalizedTenant, normalizedSubject);
            }
        }
        return find(normalizedTenant, normalizedSubject).orElseThrow();
    }

    @Override
    public boolean delete(String tenantId, String subject) {
        return jdbc.update("DELETE FROM tenant_memberships WHERE tenant_id = ? AND subject = ?",
                TenantContext.normalize(tenantId), normalizeSubject(subject)) > 0;
    }

    @Override
    public long countByRole(String tenantId, TenantRole role) {
        String name = role.name();
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM tenant_memberships
                WHERE tenant_id = ? AND (roles = ? OR roles LIKE ? OR roles LIKE ? OR roles LIKE ?)
                """, Long.class, TenantContext.normalize(tenantId), name,
                name + ",%", "%," + name, "%," + name + ",%");
        return count == null ? 0L : count;
    }

    private void ensureTenant(String tenantId, Instant now) {
        try {
            jdbc.update("INSERT INTO tenants (id, display_name, created_at, updated_at) VALUES (?, ?, ?, ?)",
                    tenantId, tenantId, Timestamp.from(now), Timestamp.from(now));
        } catch (DuplicateKeyException ignored) {
            // Tenant already exists.
        }
    }

    private TenantMembership map(ResultSet rs, int rowNum) throws SQLException {
        return new TenantMembership(
                rs.getString("tenant_id"),
                rs.getString("subject"),
                parseRoles(rs.getString("roles")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static String serializeRoles(Set<TenantRole> roles) {
        if (roles == null || roles.isEmpty()) throw new IllegalArgumentException("roles must not be empty");
        return roles.stream().sorted().map(Enum::name).collect(Collectors.joining(","));
    }

    private static Set<TenantRole> parseRoles(String roles) {
        return Arrays.stream(roles.split(","))
                .map(TenantRole::parse)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String normalizeSubject(String subject) {
        if (subject == null || subject.isBlank() || subject.length() > 190) {
            throw new IllegalArgumentException("subject must contain 1 to 190 characters");
        }
        return subject.trim();
    }
}
