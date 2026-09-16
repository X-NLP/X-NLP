package com.xnlp.server.security;

import com.xnlp.server.tenant.TenantContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class TenantAuthorizationService {

    private final TenantMembershipRepository memberships;

    public TenantAuthorizationService(TenantMembershipRepository memberships) {
        this.memberships = memberships;
    }

    public XnlpPrincipal currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof XnlpPrincipal principal)) {
            throw new AccessDeniedException("Authentication is required");
        }
        return principal;
    }

    public Set<TenantRole> effectiveRoles(String tenantId, XnlpPrincipal principal) {
        String normalizedTenant = TenantContext.normalize(tenantId);
        if (!principal.tenantId().equals(normalizedTenant)) return Set.of();
        return memberships.find(normalizedTenant, principal.subject())
                .map(TenantMembership::roles)
                .orElse(principal.roles());
    }

    public void require(String tenantId, TenantRole... allowedRoles) {
        XnlpPrincipal principal = currentPrincipal();
        Set<TenantRole> effectiveRoles = effectiveRoles(tenantId, principal);
        for (TenantRole allowedRole : allowedRoles) {
            if (effectiveRoles.contains(allowedRole)) return;
        }
        throw new AccessDeniedException("Tenant role does not permit this operation");
    }

    public TenantMembership saveMembership(String tenantId, String subject, Set<TenantRole> roles) {
        require(tenantId, TenantRole.ADMIN);
        if (roles == null || roles.isEmpty()) {
            throw TenantMembershipException.roleInvalid();
        }
        return memberships.save(tenantId, subject, roles);
    }

    public void deleteMembership(String tenantId, String subject) {
        require(tenantId, TenantRole.ADMIN);
        TenantMembership membership = memberships.find(tenantId, subject)
                .orElseThrow(TenantMembershipException::notFound);
        if (membership.roles().contains(TenantRole.ADMIN)
                && memberships.countByRole(tenantId, TenantRole.ADMIN) <= 1) {
            throw TenantMembershipException.lastAdminRequired();
        }
        memberships.delete(tenantId, subject);
    }
}
