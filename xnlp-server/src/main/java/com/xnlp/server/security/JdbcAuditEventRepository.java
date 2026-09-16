package com.xnlp.server.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xnlp.server.tenant.TenantContext;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
@Profile("!memory")
public class JdbcAuditEventRepository implements AuditEventRepository {

    private static final TypeReference<Map<String, Object>> DETAIL_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcAuditEventRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public AuditEvent append(String tenantId, String actor, String action, String resourceType,
                             String resourceId, String outcome, Map<String, Object> detail,
                             Instant occurredAt, Instant retainUntil) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO audit_events
                (id, tenant_id, actor, action, resource_type, resource_id, outcome, detail, occurred_at, retain_until)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, TenantContext.normalize(tenantId), actor, action, resourceType, resourceId, outcome,
                serialize(detail), Timestamp.from(occurredAt), timestamp(retainUntil));
        return new AuditEvent(id, TenantContext.normalize(tenantId), actor, action, resourceType,
                resourceId, outcome, detail, occurredAt, retainUntil);
    }

    @Override
    public List<AuditEvent> find(String tenantId, String actor, String action, String resourceType,
                                 Instant from, Instant to, int limit, int offset) {
        Query query = query(tenantId, actor, action, resourceType, from, to, false);
        query.sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?");
        query.args.add(limit);
        query.args.add(offset);
        return jdbc.query(query.sql.toString(), this::map, query.args.toArray());
    }

    @Override
    public long count(String tenantId, String actor, String action, String resourceType, Instant from, Instant to) {
        Query query = query(tenantId, actor, action, resourceType, from, to, true);
        Long count = jdbc.queryForObject(query.sql.toString(), Long.class, query.args.toArray());
        return count == null ? 0L : count;
    }

    private Query query(String tenantId, String actor, String action, String resourceType,
                        Instant from, Instant to, boolean count) {
        StringBuilder sql = new StringBuilder(count ? "SELECT COUNT(*) FROM audit_events WHERE tenant_id = ?"
                : "SELECT id, tenant_id, actor, action, resource_type, resource_id, outcome, detail, "
                + "occurred_at, retain_until FROM audit_events WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.normalize(tenantId));
        add(sql, args, "actor", actor);
        add(sql, args, "action", action);
        add(sql, args, "resource_type", resourceType);
        if (from != null) { sql.append(" AND occurred_at >= ?"); args.add(Timestamp.from(from)); }
        if (to != null) { sql.append(" AND occurred_at <= ?"); args.add(Timestamp.from(to)); }
        return new Query(sql, args);
    }

    private static void add(StringBuilder sql, List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) { sql.append(" AND ").append(column).append(" = ?"); args.add(value); }
    }

    private AuditEvent map(ResultSet rs, int rowNum) throws SQLException {
        return new AuditEvent(rs.getString("id"), rs.getString("tenant_id"), rs.getString("actor"),
                rs.getString("action"), rs.getString("resource_type"), rs.getString("resource_id"),
                rs.getString("outcome"), deserialize(rs.getString("detail")),
                rs.getTimestamp("occurred_at").toInstant(), instant(rs, "retain_until"));
    }

    private String serialize(Map<String, Object> detail) {
        try { return mapper.writeValueAsString(detail == null ? Map.of() : detail); }
        catch (JsonProcessingException ex) { throw new IllegalArgumentException("Audit detail is not serializable", ex); }
    }

    private Map<String, Object> deserialize(String detail) {
        if (detail == null || detail.isBlank()) return Map.of();
        try { return mapper.readValue(detail, DETAIL_TYPE); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Stored audit detail is invalid", ex); }
    }

    private static Timestamp timestamp(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant();
    }
    private record Query(StringBuilder sql, List<Object> args) {}
}
