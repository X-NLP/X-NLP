package com.xnlp.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Runtime API security settings.
 *
 * <p>Authentication is deliberately opt-in so local development and existing
 * installations remain backwards compatible. Production deployments should
 * set {@code XNLP_SECURITY_ENABLED=true} and inject one or more keys through
 * {@code XNLP_SECURITY_API_KEYS} rather than committing credentials to YAML.</p>
 */
@ConfigurationProperties(prefix = "xnlp.security")
public class SecurityProperties {

    private boolean enabled;
    private String headerName = "X-API-Key";
    private List<String> apiKeys = new ArrayList<>();

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

    public List<String> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(List<String> apiKeys) {
        this.apiKeys = apiKeys == null ? new ArrayList<>() : new ArrayList<>(apiKeys);
    }

    /**
     * Fail closed at startup instead of running an apparently protected server
     * that can never authenticate a request.
     */
    public void validate() {
        if (enabled && apiKeys.stream().noneMatch(this::hasText)) {
            throw new IllegalStateException(
                    "xnlp.security.enabled=true requires at least one xnlp.security.api-keys value");
        }
    }

    public boolean matches(String candidate) {
        if (!hasText(candidate)) {
            return false;
        }
        byte[] actual = candidate.trim().getBytes(StandardCharsets.UTF_8);
        return apiKeys.stream()
                .filter(this::hasText)
                .map(key -> key.trim().getBytes(StandardCharsets.UTF_8))
                .anyMatch(expected -> MessageDigest.isEqual(expected, actual));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
