package com.xnlp.server.controller;

import com.xnlp.server.dto.TenantMembershipRequest;
import com.xnlp.server.security.TenantAuthorizationService;
import com.xnlp.server.security.TenantMembership;
import com.xnlp.server.security.TenantMembershipRepository;
import com.xnlp.server.security.TenantRole;
import com.xnlp.server.security.XnlpPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1")
public class IdentityController {

    private final TenantAuthorizationService authorization;
    private final TenantMembershipRepository memberships;

    public IdentityController(TenantAuthorizationService authorization, TenantMembershipRepository memberships) {
        this.authorization = authorization;
        this.memberships = memberships;
    }

    @GetMapping("/auth/me")
    public CurrentIdentityResponse currentIdentity() {
        XnlpPrincipal principal = authorization.currentPrincipal();
        Set<TenantRole> roles = authorization.effectiveRoles(principal.tenantId(), principal);
        return new CurrentIdentityResponse(principal.subject(), principal.tenantId(), roles, principal.credentialType());
    }

    @GetMapping("/tenants/{tenantId}/members")
    public List<TenantMembership> listMembers(
            @PathVariable String tenantId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        authorization.require(tenantId, TenantRole.ADMIN);
        return memberships.findByTenant(tenantId, size, offset);
    }

    @PutMapping("/tenants/{tenantId}/members/{subject}")
    public TenantMembership saveMember(
            @PathVariable String tenantId,
            @PathVariable String subject,
            @Valid @RequestBody TenantMembershipRequest request) {
        return authorization.saveMembership(tenantId, subject, request.roles());
    }

    @DeleteMapping("/tenants/{tenantId}/members/{subject}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMember(@PathVariable String tenantId, @PathVariable String subject) {
        authorization.deleteMembership(tenantId, subject);
    }

    public record CurrentIdentityResponse(
            String subject,
            String activeTenant,
            Set<TenantRole> roles,
            String credentialType) {
    }
}
