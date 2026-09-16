package com.xnlp.server.config;

import com.xnlp.server.security.AuthenticationMode;
import com.xnlp.server.security.TenantRole;
import com.xnlp.server.tenant.TenantContext;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Runtime authentication, authorization and tenant-routing settings. */
@ConfigurationProperties(prefix = "xnlp.security")
public class SecurityProperties {

    private boolean enabled;
    private AuthenticationMode mode;
    private String headerName = "X-API-Key";
    private String tenantHeaderName = "X-Tenant-ID";
    private String defaultTenantId = TenantContext.DEFAULT_TENANT_ID;
    private List<String> apiKeys = new ArrayList<>();
    /** Map of tenant id -> API key. Values are compared in constant time. */
    private Map<String, String> apiKeyTenants = new LinkedHashMap<>();
    private Jwt jwt = new Jwt();

    public boolean isEnabled() {
        return effectiveMode() != AuthenticationMode.DISABLED;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public AuthenticationMode getMode() {
        return mode;
    }

    public void setMode(AuthenticationMode mode) {
        this.mode = mode;
    }

    public AuthenticationMode effectiveMode() {
        return mode == null ? (enabled ? AuthenticationMode.API_KEY : AuthenticationMode.DISABLED) : mode;
    }

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        if (hasText(headerName)) this.headerName = headerName.trim();
    }

    public String getTenantHeaderName() {
        return tenantHeaderName;
    }

    public void setTenantHeaderName(String tenantHeaderName) {
        if (hasText(tenantHeaderName)) this.tenantHeaderName = tenantHeaderName.trim();
    }

    public String getDefaultTenantId() {
        return defaultTenantId;
    }

    public void setDefaultTenantId(String defaultTenantId) {
        this.defaultTenantId = TenantContext.normalize(defaultTenantId);
    }

    public List<String> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(List<String> apiKeys) {
        this.apiKeys = apiKeys == null ? new ArrayList<>() : new ArrayList<>(apiKeys);
    }

    public Map<String, String> getApiKeyTenants() {
        return apiKeyTenants;
    }

    public void setApiKeyTenants(Map<String, String> apiKeyTenants) {
        this.apiKeyTenants = apiKeyTenants == null ? new LinkedHashMap<>() : new LinkedHashMap<>(apiKeyTenants);
    }

    public Jwt getJwt() {
        return jwt;
    }

    public void setJwt(Jwt jwt) {
        this.jwt = jwt == null ? new Jwt() : jwt;
    }

    /** Fail closed when an enabled authentication mode is incomplete. */
    public void validate() {
        defaultTenantId = TenantContext.normalize(defaultTenantId);
        AuthenticationMode effectiveMode = effectiveMode();
        if (effectiveMode.acceptsApiKey() && !hasConfiguredApiKey()) {
            throw new IllegalStateException(effectiveMode + " authentication requires at least one API key mapping");
        }
        if (effectiveMode.acceptsJwt()) {
            jwt.validate();
        }
        for (String tenantId : apiKeyTenants.keySet()) {
            TenantContext.normalize(tenantId);
        }
    }

    public ApiKeyIdentity identityFor(String candidate) {
        if (!effectiveMode().acceptsApiKey() || !hasText(candidate)) return null;
        byte[] actual = candidate.trim().getBytes(StandardCharsets.UTF_8);
        for (Map.Entry<String, String> entry : apiKeyTenants.entrySet()) {
            if (!hasText(entry.getKey()) || !hasText(entry.getValue())) continue;
            if (MessageDigest.isEqual(entry.getValue().trim().getBytes(StandardCharsets.UTF_8), actual)) {
                String tenantId = TenantContext.normalize(entry.getKey());
                return new ApiKeyIdentity("api-key:" + tenantId, tenantId,
                        Set.of(TenantRole.ADMIN, TenantRole.DEVELOPER, TenantRole.VIEWER));
            }
        }
        boolean matchesLegacyKey = apiKeys.stream()
                .filter(this::hasText)
                .map(key -> key.trim().getBytes(StandardCharsets.UTF_8))
                .anyMatch(expected -> MessageDigest.isEqual(expected, actual));
        return matchesLegacyKey
                ? new ApiKeyIdentity("api-key:" + defaultTenantId, defaultTenantId,
                Set.of(TenantRole.ADMIN, TenantRole.DEVELOPER, TenantRole.VIEWER))
                : null;
    }

    public String tenantFor(String candidate) {
        ApiKeyIdentity identity = identityFor(candidate);
        return identity == null ? null : identity.tenantId();
    }

    public boolean matches(String candidate) {
        return identityFor(candidate) != null;
    }

    private boolean hasConfiguredApiKey() {
        return apiKeys.stream().anyMatch(this::hasText)
                || apiKeyTenants.entrySet().stream()
                .anyMatch(entry -> hasText(entry.getKey()) && hasText(entry.getValue()));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record ApiKeyIdentity(String subject, String tenantId, Set<TenantRole> roles) {
        public ApiKeyIdentity {
            roles = Set.copyOf(roles);
        }
    }

    public static class Jwt {
        private String issuerUri;
        private String jwkSetUri;
        private String audience;
        private String tenantClaim = "tenant_id";
        private String rolesClaim = "roles";
        private Duration clockSkew = Duration.ofSeconds(60);
        private Set<TenantRole> defaultRoles = new LinkedHashSet<>(Set.of(TenantRole.VIEWER));

        public String getIssuerUri() {
            return issuerUri;
        }

        public void setIssuerUri(String issuerUri) {
            this.issuerUri = trimToNull(issuerUri);
        }

        public String getJwkSetUri() {
            return jwkSetUri;
        }

        public void setJwkSetUri(String jwkSetUri) {
            this.jwkSetUri = trimToNull(jwkSetUri);
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = trimToNull(audience);
        }

        public String getTenantClaim() {
            return tenantClaim;
        }

        public void setTenantClaim(String tenantClaim) {
            if (hasTextStatic(tenantClaim)) this.tenantClaim = tenantClaim.trim();
        }

        public String getRolesClaim() {
            return rolesClaim;
        }

        public void setRolesClaim(String rolesClaim) {
            if (hasTextStatic(rolesClaim)) this.rolesClaim = rolesClaim.trim();
        }

        public Duration getClockSkew() {
            return clockSkew;
        }

        public void setClockSkew(Duration clockSkew) {
            this.clockSkew = clockSkew;
        }

        public Set<TenantRole> getDefaultRoles() {
            return Set.copyOf(defaultRoles);
        }

        public void setDefaultRoles(Set<TenantRole> defaultRoles) {
            this.defaultRoles = defaultRoles == null ? new LinkedHashSet<>() : new LinkedHashSet<>(defaultRoles);
        }

        Set<TenantRole> parseRoles(Object claim) {
            Set<TenantRole> roles = new LinkedHashSet<>();
            if (claim instanceof Iterable<?> values) {
                values.forEach(value -> addRole(roles, value));
            } else if (claim instanceof String value) {
                Arrays.stream(value.split("[ ,]")).forEach(role -> addRole(roles, role));
            }
            return roles.isEmpty() ? Set.copyOf(defaultRoles) : Set.copyOf(roles);
        }

        private void validate() {
            if (!hasTextStatic(issuerUri)) throw new IllegalStateException("JWT authentication requires issuer-uri");
            if (!hasTextStatic(audience)) throw new IllegalStateException("JWT authentication requires audience");
            if (clockSkew == null || clockSkew.isNegative() || clockSkew.compareTo(Duration.ofMinutes(5)) > 0) {
                throw new IllegalStateException("JWT clock-skew must be between 0 and 5 minutes");
            }
            if (defaultRoles.isEmpty()) throw new IllegalStateException("JWT default-roles must not be empty");
        }

        private static void addRole(Set<TenantRole> roles, Object value) {
            if (value != null && !value.toString().isBlank()) roles.add(TenantRole.parse(value.toString()));
        }

        private static String trimToNull(String value) {
            return hasTextStatic(value) ? value.trim() : null;
        }

        private static boolean hasTextStatic(String value) {
            return value != null && !value.isBlank();
        }
    }
}
