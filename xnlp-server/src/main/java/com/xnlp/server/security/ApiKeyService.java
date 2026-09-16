package com.xnlp.server.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ApiKeyService {
    public static final int MAX_KEYS_PER_TENANT = 100;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository keys;
    private final AuditEventRepository audit;
    private final TenantAuthorizationService authorization;
    private final Clock clock;

    @Autowired
    public ApiKeyService(ApiKeyRepository keys, AuditEventRepository audit,
                         TenantAuthorizationService authorization) {
        this(keys, audit, authorization, Clock.systemUTC());
    }

    ApiKeyService(ApiKeyRepository keys, AuditEventRepository audit,
                  TenantAuthorizationService authorization, Clock clock) {
        this.keys = keys; this.audit = audit; this.authorization = authorization; this.clock = clock;
    }

    public CreatedApiKey create(String name, Set<TenantRole> roles, Instant expiresAt) {
        XnlpPrincipal principal = authorization.currentPrincipal();
        authorization.require(principal.tenantId(), TenantRole.ADMIN);
        return createFor(principal, name, roles, expiresAt, null);
    }

    public List<ApiKeyRecord> list(int limit, int offset) {
        XnlpPrincipal principal = authorization.currentPrincipal();
        authorization.require(principal.tenantId(), TenantRole.ADMIN);
        return keys.findByTenant(principal.tenantId(), limit, offset);
    }

    public long count() {
        XnlpPrincipal principal = authorization.currentPrincipal();
        authorization.require(principal.tenantId(), TenantRole.ADMIN);
        return keys.countByTenant(principal.tenantId());
    }

    public CreatedApiKey rotate(String id, long gracePeriodSeconds) {
        if (gracePeriodSeconds < 0 || gracePeriodSeconds > 86400) {
            throw SecurityResourceException.apiKeyInvalid("gracePeriodSeconds must be between 0 and 86400");
        }
        XnlpPrincipal principal = authorization.currentPrincipal();
        authorization.require(principal.tenantId(), TenantRole.ADMIN);
        ApiKeyRecord existing = keys.findById(principal.tenantId(), id)
                .orElseThrow(SecurityResourceException::apiKeyNotFound);
        Instant now = clock.instant();
        Instant revokeAt = now.plusSeconds(gracePeriodSeconds);
        CreatedApiKey created = createFor(principal, existing.name(), existing.roles(), existing.expiresAt(), existing.id());
        ApiKeyRecord replacement = created.record();
        String secret = created.secret();
        keys.scheduleRevocation(existing.tenantId(), existing.id(),
                "rotated; grace until " + revokeAt, revokeAt, now);
        audit.append(existing.tenantId(), principal.subject(), "api_key.rotated", "api_key", existing.id(),
                "success", Map.of("replacementId", replacement.id(), "gracePeriodSeconds", gracePeriodSeconds), now, null);
        return new CreatedApiKey(replacement, secret);
    }

    public void revoke(String id, String reason) {
        XnlpPrincipal principal = authorization.currentPrincipal();
        authorization.require(principal.tenantId(), TenantRole.ADMIN);
        String normalizedReason = reason == null || reason.isBlank() ? "revoked by administrator" : reason.trim();
        Instant now = clock.instant();
        if (!keys.revoke(principal.tenantId(), id, normalizedReason, now)) {
            throw SecurityResourceException.apiKeyNotFound();
        }
        audit.append(principal.tenantId(), principal.subject(), "api_key.revoked", "api_key", id,
                "success", Map.of("reason", normalizedReason), now, null);
    }

    public AuthenticatedApiKey authenticate(String secret) {
        if (secret == null || secret.isBlank()) return null;
        Instant now = clock.instant();
        ApiKeyRecord key = keys.findActiveByHash(hash(secret.trim()), now).orElse(null);
        if (key == null) return null;
        keys.updateLastUsed(key.id(), now);
        audit.append(key.tenantId(), "api-key:" + key.id(), "api_key.authenticated", "api_key", key.id(),
                "success", Map.of(), now, null);
        return new AuthenticatedApiKey(key.id(), key.tenantId(), key.roles());
    }

    public List<AuditEvent> auditEvents(String actor, String action, String resourceType,
                                        Instant from, Instant to, int limit, int offset) {
        XnlpPrincipal principal = authorization.currentPrincipal();
        authorization.require(principal.tenantId(), TenantRole.ADMIN);
        return audit.find(principal.tenantId(), actor, action, resourceType, from, to, limit, offset);
    }

    public long auditCount(String actor, String action, String resourceType, Instant from, Instant to) {
        XnlpPrincipal principal = authorization.currentPrincipal();
        authorization.require(principal.tenantId(), TenantRole.ADMIN);
        return audit.count(principal.tenantId(), actor, action, resourceType, from, to);
    }

    private CreatedApiKey createFor(XnlpPrincipal principal, String name, Set<TenantRole> roles,
                                       Instant expiresAt, String rotationSourceId) {
        validate(name, roles, expiresAt);
        if (keys.countByTenant(principal.tenantId()) >= MAX_KEYS_PER_TENANT) {
            throw SecurityResourceException.apiKeyLimitExceeded();
        }
        Instant now = clock.instant();
        String secret = generateSecret();
        ApiKeyRecord record = keys.create(UUID.randomUUID().toString(), principal.tenantId(), name.trim(),
                prefix(secret), hash(secret), roles, expiresAt, principal.subject(), now);
        if (rotationSourceId == null) {
            audit.append(principal.tenantId(), principal.subject(), "api_key.created", "api_key", record.id(),
                    "success", Map.of("name", record.name(), "roles", record.roles()), now, null);
        }
        return new CreatedApiKey(record, secret);
    }

    private void validate(String name, Set<TenantRole> roles, Instant expiresAt) {
        if (name == null || name.isBlank() || name.trim().length() > 190) {
            throw SecurityResourceException.apiKeyInvalid("name must contain 1 to 190 characters");
        }
        if (roles == null || roles.isEmpty()) throw SecurityResourceException.apiKeyInvalid("roles must not be empty");
        if (expiresAt != null && !expiresAt.isAfter(clock.instant())) {
            throw SecurityResourceException.apiKeyInvalid("expiresAt must be in the future");
        }
    }

    static String hash(String secret) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 is unavailable", ex); }
    }

    private static String generateSecret() {
        byte[] random = new byte[32]; RANDOM.nextBytes(random);
        return "xnlp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private static String prefix(String secret) { return secret.substring(0, Math.min(secret.length(), 12)); }

    public record CreatedApiKey(ApiKeyRecord record, String secret) {}
    public record AuthenticatedApiKey(String id, String tenantId, Set<TenantRole> roles) {}
}
