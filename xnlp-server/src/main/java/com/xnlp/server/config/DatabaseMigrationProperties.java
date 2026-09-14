package com.xnlp.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime controls for the portable JDBC migration runner.
 */
@ConfigurationProperties(prefix = "xnlp.database.migration")
public class DatabaseMigrationProperties {

    /**
     * Whether X-NLP should apply its versioned schema migrations at startup.
     */
    private boolean enabled = true;

    /**
     * Whether already-applied migration checksums must match the current code.
     */
    private boolean validateChecksums = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isValidateChecksums() {
        return validateChecksums;
    }

    public void setValidateChecksums(boolean validateChecksums) {
        this.validateChecksums = validateChecksums;
    }
}
