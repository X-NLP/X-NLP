package com.xnlp.server.dto;

import com.xnlp.server.security.TenantRole;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

public record TenantMembershipRequest(@NotEmpty Set<TenantRole> roles) {
    public TenantMembershipRequest {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
