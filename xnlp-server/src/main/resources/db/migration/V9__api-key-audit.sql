CREATE TABLE IF NOT EXISTS api_keys (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    name VARCHAR(190) NOT NULL,
    secret_prefix VARCHAR(32) NOT NULL,
    secret_hash VARCHAR(64) NOT NULL,
    roles VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP,
    revoked_at TIMESTAMP,
    revoke_reason VARCHAR(255),
    last_used_at TIMESTAMP,
    created_by VARCHAR(190) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT api_keys_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS audit_events (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    actor VARCHAR(190) NOT NULL,
    action VARCHAR(96) NOT NULL,
    resource_type VARCHAR(96) NOT NULL,
    resource_id VARCHAR(190),
    outcome VARCHAR(32) NOT NULL,
    detail __LARGE_TEXT__,
    occurred_at TIMESTAMP NOT NULL,
    retain_until TIMESTAMP,
    CONSTRAINT audit_events_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE
);
