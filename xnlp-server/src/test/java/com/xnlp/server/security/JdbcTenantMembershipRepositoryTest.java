package com.xnlp.server.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:xnlp-membership-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=never",
                "spring.ai.model.chat=none",
                "spring.ai.openai.api-key=test-key"
        })
@ActiveProfiles("h2")
@DisplayName("Portable JDBC tenant membership repository")
class JdbcTenantMembershipRepositoryTest {

    @Autowired
    private JdbcTenantMembershipRepository memberships;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearMemberships() {
        jdbc.update("DELETE FROM tenant_memberships");
        jdbc.update("DELETE FROM tenants WHERE id <> 'default'");
    }

    @Test
    void countByRole_MatchesCompleteRoleTokensOnly() {
        memberships.save("tenant-a", "admin-a", Set.of(TenantRole.ADMIN));
        memberships.save("tenant-a", "admin-b", Set.of(TenantRole.ADMIN, TenantRole.VIEWER));
        memberships.save("tenant-a", "viewer", Set.of(TenantRole.VIEWER));
        memberships.save("tenant-b", "other-admin", Set.of(TenantRole.ADMIN));

        assertThat(memberships.countByRole("tenant-a", TenantRole.ADMIN)).isEqualTo(2);
        assertThat(memberships.countByRole("tenant-a", TenantRole.VIEWER)).isEqualTo(2);
        assertThat(memberships.countByRole("tenant-a", TenantRole.DEVELOPER)).isZero();
    }
}
