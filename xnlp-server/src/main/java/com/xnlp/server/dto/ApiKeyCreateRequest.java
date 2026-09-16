package com.xnlp.server.dto;

import com.xnlp.server.security.TenantRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Set;

public record ApiKeyCreateRequest(
        @NotBlank @Size(max = 190) String name,
        @NotEmpty Set<TenantRole> roles,
        Instant expiresAt) {

    public ApiKeyCreateRequest {
        roles = roles == null ? null : Set.copyOf(roles);
    }
}
