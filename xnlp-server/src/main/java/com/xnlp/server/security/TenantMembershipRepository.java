package com.xnlp.server.security;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface TenantMembershipRepository {
    Optional<TenantMembership> find(String tenantId, String subject);

    List<TenantMembership> findByTenant(String tenantId, int limit, int offset);

    TenantMembership save(String tenantId, String subject, Set<TenantRole> roles);

    boolean delete(String tenantId, String subject);

    long countByRole(String tenantId, TenantRole role);
}
