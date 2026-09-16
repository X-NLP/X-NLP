package com.xnlp.server.security;

import com.xnlp.server.tenant.TenantContext;

import java.security.Principal;
import java.util.Objects;
import java.util.Set;

public record XnlpPrincipal(
        String subject,
        String tenantId,
        Set<TenantRole> roles,
        String credentialType) implements Principal {

    public XnlpPrincipal {
        subject = requireText(subject, "subject");
        tenantId = TenantContext.normalize(tenantId);
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("At least one tenant role is required");
        }
        credentialType = requireText(credentialType, "credentialType");
    }

    @Override
    public String getName() {
        return subject;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
