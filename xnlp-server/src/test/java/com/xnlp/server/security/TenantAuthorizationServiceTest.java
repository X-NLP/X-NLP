package com.xnlp.server.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantAuthorizationServiceTest {

    private final InMemoryTenantMembershipRepository memberships = new InMemoryTenantMembershipRepository();
    private final TenantAuthorizationService service = new TenantAuthorizationService(memberships);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void require_CrossTenant_IsDenied() {
        authenticate(new XnlpPrincipal("alice", "tenant-a", Set.of(TenantRole.ADMIN), "jwt"));

        assertThatThrownBy(() -> service.require("tenant-b", TenantRole.ADMIN))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void effectiveRoles_PersistedMembershipOverridesTokenRoles() {
        memberships.save("tenant-a", "alice", Set.of(TenantRole.VIEWER));
        XnlpPrincipal principal = new XnlpPrincipal(
                "alice", "tenant-a", Set.of(TenantRole.ADMIN), "jwt");
        authenticate(principal);

        assertThat(service.effectiveRoles("tenant-a", principal)).containsExactly(TenantRole.VIEWER);
        assertThatThrownBy(() -> service.require("tenant-a", TenantRole.ADMIN))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void deleteMembership_LastAdministrator_IsRejected() {
        memberships.save("tenant-a", "alice", Set.of(TenantRole.ADMIN));
        authenticate(new XnlpPrincipal("alice", "tenant-a", Set.of(TenantRole.ADMIN), "jwt"));

        assertThatThrownBy(() -> service.deleteMembership("tenant-a", "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("administrator");
    }

    private void authenticate(XnlpPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null,
                        principal.roles().stream()
                                .map(role -> new SimpleGrantedAuthority(role.authority())).toList()));
    }
}
