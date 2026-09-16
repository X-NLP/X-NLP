package com.xnlp.server.config;

import com.xnlp.server.security.AuthenticationMode;
import com.xnlp.server.security.TenantRole;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityPropertiesTest {

    @Test
    void effectiveMode_LegacyEnabled_UsesApiKey() {
        SecurityProperties properties = new SecurityProperties();
        properties.setEnabled(true);
        properties.setApiKeys(List.of("secret"));

        properties.validate();

        assertThat(properties.effectiveMode()).isEqualTo(AuthenticationMode.API_KEY);
        assertThat(properties.identityFor("secret").roles())
                .containsExactlyInAnyOrder(TenantRole.ADMIN, TenantRole.DEVELOPER, TenantRole.VIEWER);
    }

    @Test
    void validate_JwtWithoutIssuerAndAudience_FailsClosed() {
        SecurityProperties properties = new SecurityProperties();
        properties.setMode(AuthenticationMode.JWT);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("issuer-uri");
    }

    @Test
    void validate_JwtConfiguration_AcceptsBoundedClockSkew() {
        SecurityProperties properties = jwtProperties();
        properties.getJwt().setClockSkew(Duration.ofSeconds(30));

        properties.validate();

        assertThat(properties.getJwt().parseRoles("developer viewer"))
                .containsExactlyInAnyOrder(TenantRole.DEVELOPER, TenantRole.VIEWER);
    }

    @Test
    void identityFor_TenantMapping_DoesNotTrustRequestedTenant() {
        SecurityProperties properties = new SecurityProperties();
        properties.setMode(AuthenticationMode.API_KEY);
        properties.setApiKeyTenants(Map.of("tenant-a", "secret-a"));
        properties.validate();

        assertThat(properties.identityFor("secret-a").tenantId()).isEqualTo("tenant-a");
        assertThat(properties.identityFor("wrong")).isNull();
    }

    @Test
    void validate_ExcessiveJwtClockSkew_FailsClosed() {
        SecurityProperties properties = jwtProperties();
        properties.getJwt().setClockSkew(Duration.ofMinutes(6));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("clock-skew");
    }

    private SecurityProperties jwtProperties() {
        SecurityProperties properties = new SecurityProperties();
        properties.setMode(AuthenticationMode.JWT);
        properties.getJwt().setIssuerUri("https://issuer.example.test");
        properties.getJwt().setAudience("xnlp-api");
        properties.getJwt().setDefaultRoles(Set.of(TenantRole.VIEWER));
        return properties;
    }
}
