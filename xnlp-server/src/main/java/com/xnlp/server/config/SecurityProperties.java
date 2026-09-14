package com.xnlp.server.config;

import com.xnlp.server.tenant.TenantContext;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime API security and tenant routing settings.
 *
 * <p>Authentication is deliberately opt-in so local development and existing
 * installations remain backwards compatible. Production deployments should
 * set {@code XNLP_SECURITY_ENABLED=true} and inject keys through
 * {@code XNLP_SECURITY_API_KEYS} or tenant mappings through
 * {@code XNLP_SECURITY_API_KEY_TENANTS}.</p>
 */
@ConfigurationProperties(prefix = "xnlp.security")
public class SecurityProperties {

    private boolean enabled;
    private String headerName = "X-API-Key";
    private String tenantHeaderName = "X-Tenant-ID";
    private String defaultTenantId = TenantContext.DEFAULT_TENANT_ID;
    private List<String> apiKeys = new ArrayList<>();
    /** Map of tenant id -> API key. Values are compared in constant time. */
    private Map<String, String> apiKeyTenants = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        if (headerName != null && !headerName.isBlank()) {
            this.headerName = headerName.trim();
        }
    }

    public String getTenantHeaderName() {
        return tenantHeaderName;
    }

    public void setTenantHeaderName(String tenantHeaderName) {
        if (tenantHeaderName != null && !tenantHeaderName.isBlank()) {
            this.tenantHeaderName = tenantHeaderName.trim();
        }
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
        this.apiKeyTenants = apiKeyTenants == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(apiKeyTenants);
    }

    /**
     * Fail closed at startup instead of running an apparently protected server
     * that can never authenticate a request.
     */
    public void validate() {
        defaultTenantId = TenantContext.normalize(defaultTenantId);
        if (enabled && apiKeys.stream().noneMatch(this::hasText)
                && apiKeyTenants.entrySet().stream().noneMatch(entry -> hasText(entry.getKey()) && hasText(entry.getValue()))) {
            throw new IllegalStateException(
                    "xnlp.security.enabled=true requires at least one API key or tenant API key mapping");
        }
        for (String tenantId : apiKeyTenants.keySet()) {
            TenantContext.normalize(tenantId);
        }
    }

    /** Return the tenant bound to a key, or {@code null} when the key is invalid. */
    public String tenantFor(String candidate) {
        if (!hasText(candidate)) {
            return null;
        }
        byte[] actual = candidate.trim().getBytes(StandardCharsets.UTF_8);
        for (Map.Entry<String, String> entry : apiKeyTenants.entrySet()) {
            if (!hasText(entry.getKey()) || !hasText(entry.getValue())) continue;
            byte[] expected = entry.getValue().trim().getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(expected, actual)) {
                return TenantContext.normalize(entry.getKey());
            }
        }
        return apiKeys.stream()
                .filter(this::hasText)
                .map(key -> key.trim().getBytes(StandardCharsets.UTF_8))
                .anyMatch(expected -> MessageDigest.isEqual(expected, actual))
                ? defaultTenantId : null;
    }

    public boolean matches(String candidate) {
        return tenantFor(candidate) != null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
