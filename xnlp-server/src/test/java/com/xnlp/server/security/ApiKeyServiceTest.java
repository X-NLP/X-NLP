package com.xnlp.server.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyServiceTest {

    private static final Instant START = Instant.parse("2026-09-16T08:00:00Z");

    private final InMemoryApiKeyRepository keys = new InMemoryApiKeyRepository();
    private final InMemoryAuditEventRepository audit = new InMemoryAuditEventRepository();
    private final TenantAuthorizationService authorization =
            new TenantAuthorizationService(new InMemoryTenantMembershipRepository());
    private final MutableClock clock = new MutableClock(START);
    private final ApiKeyService service = new ApiKeyService(keys, audit, authorization, clock);

    @BeforeEach
    void authenticateAdministrator() {
        XnlpPrincipal principal = new XnlpPrincipal(
                "alice", "tenant-a", Set.of(TenantRole.ADMIN), "jwt");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null,
                        principal.roles().stream()
                                .map(role -> new SimpleGrantedAuthority(role.authority())).toList()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createRotateAndRevoke_EnforcesSecretLifecycleAndWritesAuditTrail() {
        ApiKeyService.CreatedApiKey original = service.create(
                "deployment", Set.of(TenantRole.DEVELOPER), START.plusSeconds(3600));

        assertThat(original.secret()).startsWith("xnlp_");
        assertThat(original.record().tenantId()).isEqualTo("tenant-a");
        assertThat(original.record().secretHash()).isEqualTo(ApiKeyService.hash(original.secret()));
        assertThat(original.record().secretHash()).doesNotContain(original.secret());
        assertThat(service.authenticate(original.secret()))
                .isEqualTo(new ApiKeyService.AuthenticatedApiKey(
                        original.record().id(), "tenant-a", Set.of(TenantRole.DEVELOPER)));
        assertThat(keys.findById("tenant-a", original.record().id()))
                .hasValueSatisfying(record -> assertThat(record.lastUsedAt()).isEqualTo(START));

        ApiKeyService.CreatedApiKey replacement = service.rotate(original.record().id(), 30);
        assertThat(replacement.secret()).isNotEqualTo(original.secret());
        assertThat(replacement.record().id()).isNotEqualTo(original.record().id());
        assertThat(service.authenticate(original.secret())).isNotNull();
        assertThat(service.authenticate(replacement.secret())).isNotNull();

        clock.advance(Duration.ofSeconds(31));
        assertThat(service.authenticate(original.secret())).isNull();
        assertThat(service.authenticate(replacement.secret())).isNotNull();

        service.revoke(replacement.record().id(), "credential retired");
        assertThat(service.authenticate(replacement.secret())).isNull();
        assertThat(keys.findById("tenant-a", replacement.record().id()))
                .hasValueSatisfying(record -> {
                    assertThat(record.revokedAt()).isEqualTo(clock.instant());
                    assertThat(record.revokeReason()).isEqualTo("credential retired");
                });

        assertThat(audit.find("tenant-a", "alice", null, "api_key", null, null, 20, 0))
                .extracting(AuditEvent::action)
                .containsExactlyInAnyOrder("api_key.created", "api_key.rotated", "api_key.revoked");
        assertThat(audit.count("tenant-a", null, "api_key.authenticated", "api_key", null, null))
                .isEqualTo(4);
        assertThat(audit.count("tenant-b", null, null, null, null, null)).isZero();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
