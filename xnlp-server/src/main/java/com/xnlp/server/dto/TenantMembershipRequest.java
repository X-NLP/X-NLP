package com.xnlp.server.dto;

import com.xnlp.server.security.TenantRole;
import java.util.Set;

public record TenantMembershipRequest(Set<TenantRole> roles) {
    public TenantMembershipRequest {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
