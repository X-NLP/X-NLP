package com.xnlp.server.quota;

import com.xnlp.server.tenant.TenantContext;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
@Profile("!memory")
public class JdbcTenantQuotaRepository implements TenantQuotaRepository {

    private final JdbcTemplate jdbc;

    public JdbcTenantQuotaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TenantQuota> findLimits(String tenantId) {
        List<TenantQuota> results = jdbc.query("""
                SELECT tenant_id, request_limit_per_minute, model_call_limit_per_minute,
                       knowledge_import_limit_per_hour, concurrent_request_limit, created_at, updated_at
                FROM tenant_quotas WHERE tenant_id = ?
                """, this::mapQuota, TenantContext.normalize(tenantId));
        return results.stream().findFirst();
    }

    @Override
    public TenantQuota saveLimits(String tenantId, QuotaLimits limits) {
        String normalizedTenant = TenantContext.normalize(tenantId);
        Objects.requireNonNull(limits, "limits");
        Instant now = Instant.now();
        int updated = jdbc.update("""
                UPDATE tenant_quotas
                SET request_limit_per_minute = ?, model_call_limit_per_minute = ?,
                    knowledge_import_limit_per_hour = ?, concurrent_request_limit = ?, updated_at = ?
                WHERE tenant_id = ?
                """, limits.requestsPerMinute(), limits.modelCallsPerMinute(), limits.knowledgeImportsPerHour(),
                limits.concurrentRequests(), timestamp(now), normalizedTenant);
        if (updated == 0) {
            ensureTenant(normalizedTenant, now);
            try {
                jdbc.update("""
                        INSERT INTO tenant_quotas (
                            tenant_id, request_limit_per_minute, model_call_limit_per_minute,
                            knowledge_import_limit_per_hour, concurrent_request_limit, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, normalizedTenant, limits.requestsPerMinute(), limits.modelCallsPerMinute(),
                        limits.knowledgeImportsPerHour(), limits.concurrentRequests(), timestamp(now), timestamp(now));
            } catch (DuplicateKeyException ignored) {
                jdbc.update("""
                        UPDATE tenant_quotas
                        SET request_limit_per_minute = ?, model_call_limit_per_minute = ?,
                            knowledge_import_limit_per_hour = ?, concurrent_request_limit = ?, updated_at = ?
                        WHERE tenant_id = ?
                        """, limits.requestsPerMinute(), limits.modelCallsPerMinute(), limits.knowledgeImportsPerHour(),
                        limits.concurrentRequests(), timestamp(now), normalizedTenant);
            }
        }
        return findLimits(normalizedTenant).orElseThrow();
    }

    @Override
    public Optional<QuotaUsageWindow> findUsageWindow(
            String tenantId, QuotaDimension dimension, Instant windowStart) {
        List<QuotaUsageWindow> results = jdbc.query("""
                SELECT tenant_id, quota_dimension, window_start, window_end, used_units, limit_units, updated_at
                FROM quota_usage_windows
                WHERE tenant_id = ? AND quota_dimension = ? AND window_start = ?
                """, this::mapUsage, TenantContext.normalize(tenantId), requireDimension(dimension).storageValue(),
                timestamp(windowStart));
        return results.stream().findFirst();
    }

    @Override
    public QuotaClaim tryClaimUsage(
            String tenantId,
            QuotaDimension dimension,
            Instant windowStart,
            Instant windowEnd,
            long units) {
        String normalizedTenant = TenantContext.normalize(tenantId);
        validateWindow(windowStart, windowEnd);
        if (units < 1) throw new IllegalArgumentException("units must be positive");
        QuotaDimension normalizedDimension = requireDimension(dimension);
        long limit = findLimits(normalizedTenant)
                .map(quota -> quota.limits().limitFor(normalizedDimension))
                .orElse(0L);
        if (units > limit) return deniedClaim(normalizedTenant, normalizedDimension, windowStart, windowEnd, limit);

        Instant now = Instant.now();
        if (incrementUsage(normalizedTenant, normalizedDimension, windowStart, windowEnd, units, limit, now)) {
            return grantedClaim(normalizedTenant, normalizedDimension, windowStart, windowEnd, limit);
        }
        try {
            jdbc.update("""
                    INSERT INTO quota_usage_windows (
                        tenant_id, quota_dimension, window_start, window_end, used_units, limit_units, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, normalizedTenant, normalizedDimension.storageValue(), timestamp(windowStart),
                    timestamp(windowEnd), units, limit, timestamp(now));
            return new QuotaClaim(true, units, limit, windowEnd);
        } catch (DuplicateKeyException ignored) {
            if (incrementUsage(normalizedTenant, normalizedDimension, windowStart, windowEnd, units, limit, now)) {
                return grantedClaim(normalizedTenant, normalizedDimension, windowStart, windowEnd, limit);
            }
            return deniedClaim(normalizedTenant, normalizedDimension, windowStart, windowEnd, limit);
        }
    }

    @Override
    public Optional<ConcurrencyLease> findLease(String tenantId, String leaseId) {
        List<ConcurrencyLease> results = jdbc.query("""
                SELECT tenant_id, lease_id, slot_no, owner_id, acquired_at, expires_at
                FROM quota_concurrency_leases WHERE tenant_id = ? AND lease_id = ?
                """, this::mapLease, TenantContext.normalize(tenantId), normalizeLeaseId(leaseId));
        return results.stream().findFirst();
    }

    @Override
    public Optional<ConcurrencyLease> tryAcquireLease(
            String tenantId,
            String leaseId,
            String ownerId,
            Instant acquiredAt,
            Instant expiresAt) {
        String normalizedTenant = TenantContext.normalize(tenantId);
        String normalizedLease = normalizeLeaseId(leaseId);
        String normalizedOwner = normalizeOwnerId(ownerId);
        validateLeaseTimes(acquiredAt, expiresAt);
        int limit = findLimits(normalizedTenant).map(quota -> quota.limits().concurrentRequests()).orElse(0);
        if (limit == 0) return Optional.empty();

        Optional<ConcurrencyLease> existing = findLease(normalizedTenant, normalizedLease);
        if (existing.filter(lease -> lease.activeAt(acquiredAt)).isPresent()) {
            return existing.filter(lease -> lease.ownerId().equals(normalizedOwner));
        }

        for (int slot = 0; slot < limit; slot++) {
            try {
                int reclaimed = jdbc.update("""
                        UPDATE quota_concurrency_leases
                        SET lease_id = ?, owner_id = ?, acquired_at = ?, expires_at = ?
                        WHERE tenant_id = ? AND slot_no = ? AND expires_at <= ?
                        """, normalizedLease, normalizedOwner, timestamp(acquiredAt), timestamp(expiresAt),
                        normalizedTenant, slot, timestamp(acquiredAt));
                if (reclaimed == 1) return findLease(normalizedTenant, normalizedLease);

                jdbc.update("""
                        INSERT INTO quota_concurrency_leases (
                            tenant_id, lease_id, slot_no, owner_id, acquired_at, expires_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, normalizedTenant, normalizedLease, slot, normalizedOwner,
                        timestamp(acquiredAt), timestamp(expiresAt));
                return findLease(normalizedTenant, normalizedLease);
            } catch (DuplicateKeyException ignored) {
                Optional<ConcurrencyLease> acquired = findLease(normalizedTenant, normalizedLease)
                        .filter(lease -> lease.ownerId().equals(normalizedOwner) && lease.activeAt(acquiredAt));
                if (acquired.isPresent()) return acquired;
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean releaseLease(String tenantId, String leaseId) {
        return jdbc.update("DELETE FROM quota_concurrency_leases WHERE tenant_id = ? AND lease_id = ?",
                TenantContext.normalize(tenantId), normalizeLeaseId(leaseId)) == 1;
    }

    @Override
    public int deleteExpiredLeases(Instant expiredAtOrBefore) {
        return jdbc.update("DELETE FROM quota_concurrency_leases WHERE expires_at <= ?",
                timestamp(expiredAtOrBefore));
    }

    @Override
    public long countActiveLeases(String tenantId, Instant at) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM quota_concurrency_leases WHERE tenant_id = ? AND expires_at > ?
                """, Long.class, TenantContext.normalize(tenantId), timestamp(at));
        return count == null ? 0L : count;
    }

    private boolean incrementUsage(
            String tenantId,
            QuotaDimension dimension,
            Instant windowStart,
            Instant windowEnd,
            long units,
            long limit,
            Instant now) {
        return jdbc.update("""
                UPDATE quota_usage_windows
                SET used_units = used_units + ?, limit_units = ?, updated_at = ?
                WHERE tenant_id = ? AND quota_dimension = ? AND window_start = ? AND window_end = ?
                  AND used_units <= ?
                """, units, limit, timestamp(now), tenantId, dimension.storageValue(), timestamp(windowStart),
                timestamp(windowEnd), limit - units) == 1;
    }

    private QuotaClaim grantedClaim(
            String tenantId, QuotaDimension dimension, Instant windowStart, Instant windowEnd, long limit) {
        QuotaUsageWindow usage = requireMatchingWindow(tenantId, dimension, windowStart, windowEnd);
        return new QuotaClaim(true, usage.usedUnits(), limit, windowEnd);
    }

    private QuotaClaim deniedClaim(
            String tenantId, QuotaDimension dimension, Instant windowStart, Instant windowEnd, long limit) {
        Optional<QuotaUsageWindow> usage = findUsageWindow(tenantId, dimension, windowStart);
        usage.ifPresent(existing -> requireSameWindow(existing, windowEnd));
        return new QuotaClaim(false, usage.map(QuotaUsageWindow::usedUnits).orElse(0L), limit, windowEnd);
    }

    private QuotaUsageWindow requireMatchingWindow(
            String tenantId, QuotaDimension dimension, Instant windowStart, Instant windowEnd) {
        QuotaUsageWindow usage = findUsageWindow(tenantId, dimension, windowStart).orElseThrow();
        requireSameWindow(usage, windowEnd);
        return usage;
    }

    private static void requireSameWindow(QuotaUsageWindow usage, Instant windowEnd) {
        if (!usage.windowEnd().equals(windowEnd)) {
            throw new IllegalArgumentException("A quota window start cannot be reused with a different end");
        }
    }

    private void ensureTenant(String tenantId, Instant now) {
        try {
            jdbc.update("INSERT INTO tenants (id, display_name, created_at, updated_at) VALUES (?, ?, ?, ?)",
                    tenantId, tenantId, timestamp(now), timestamp(now));
        } catch (DuplicateKeyException ignored) {
            // Tenant already exists.
        }
    }

    private TenantQuota mapQuota(ResultSet rs, int rowNum) throws SQLException {
        return new TenantQuota(rs.getString("tenant_id"), new QuotaLimits(
                rs.getLong("request_limit_per_minute"),
                rs.getLong("model_call_limit_per_minute"),
                rs.getLong("knowledge_import_limit_per_hour"),
                rs.getInt("concurrent_request_limit")),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private QuotaUsageWindow mapUsage(ResultSet rs, int rowNum) throws SQLException {
        return new QuotaUsageWindow(rs.getString("tenant_id"),
                QuotaDimension.fromStorageValue(rs.getString("quota_dimension")),
                rs.getTimestamp("window_start").toInstant(), rs.getTimestamp("window_end").toInstant(),
                rs.getLong("used_units"), rs.getLong("limit_units"), rs.getTimestamp("updated_at").toInstant());
    }

    private ConcurrencyLease mapLease(ResultSet rs, int rowNum) throws SQLException {
        return new ConcurrencyLease(rs.getString("tenant_id"), rs.getString("lease_id"), rs.getInt("slot_no"),
                rs.getString("owner_id"), rs.getTimestamp("acquired_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant());
    }

    private static QuotaDimension requireDimension(QuotaDimension dimension) {
        return Objects.requireNonNull(dimension, "dimension");
    }

    private static void validateWindow(Instant windowStart, Instant windowEnd) {
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(windowEnd, "windowEnd");
        if (!windowEnd.isAfter(windowStart)) throw new IllegalArgumentException("windowEnd must be after windowStart");
    }

    private static void validateLeaseTimes(Instant acquiredAt, Instant expiresAt) {
        Objects.requireNonNull(acquiredAt, "acquiredAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(acquiredAt)) throw new IllegalArgumentException("expiresAt must be after acquiredAt");
    }

    private static String normalizeLeaseId(String leaseId) {
        String value = ConcurrencyLease.requireText(leaseId, "leaseId");
        if (value.length() > 96) throw new IllegalArgumentException("leaseId must not exceed 96 characters");
        return value;
    }

    private static String normalizeOwnerId(String ownerId) {
        String value = ConcurrencyLease.requireText(ownerId, "ownerId");
        if (value.length() > 190) throw new IllegalArgumentException("ownerId must not exceed 190 characters");
        return value;
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(Objects.requireNonNull(instant, "instant"));
    }
}
