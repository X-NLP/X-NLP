CREATE TABLE IF NOT EXISTS tenant_quotas (
    tenant_id VARCHAR(64) PRIMARY KEY,
    request_limit_per_minute BIGINT NOT NULL,
    model_call_limit_per_minute BIGINT NOT NULL,
    knowledge_import_limit_per_hour BIGINT NOT NULL,
    concurrent_request_limit INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT tenant_quotas_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT tenant_quotas_request_limit_check CHECK (request_limit_per_minute >= 0),
    CONSTRAINT tenant_quotas_model_limit_check CHECK (model_call_limit_per_minute >= 0),
    CONSTRAINT tenant_quotas_import_limit_check CHECK (knowledge_import_limit_per_hour >= 0),
    CONSTRAINT tenant_quotas_concurrency_limit_check CHECK (concurrent_request_limit >= 0)
);

CREATE TABLE IF NOT EXISTS quota_usage_windows (
    tenant_id VARCHAR(64) NOT NULL,
    quota_dimension VARCHAR(32) NOT NULL,
    window_start TIMESTAMP NOT NULL,
    window_end TIMESTAMP NOT NULL,
    used_units BIGINT NOT NULL,
    limit_units BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, quota_dimension, window_start),
    CONSTRAINT quota_usage_windows_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT quota_usage_windows_used_check CHECK (used_units >= 0),
    CONSTRAINT quota_usage_windows_limit_check CHECK (limit_units >= 0),
    CONSTRAINT quota_usage_windows_time_check CHECK (window_end > window_start)
);

CREATE INDEX quota_usage_windows_expiry ON quota_usage_windows(window_end);

CREATE TABLE IF NOT EXISTS quota_concurrency_leases (
    tenant_id VARCHAR(64) NOT NULL,
    lease_id VARCHAR(96) NOT NULL,
    slot_no INTEGER NOT NULL,
    owner_id VARCHAR(190) NOT NULL,
    acquired_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, lease_id),
    CONSTRAINT quota_concurrency_leases_slot_unique UNIQUE (tenant_id, slot_no),
    CONSTRAINT quota_concurrency_leases_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT quota_concurrency_leases_slot_check CHECK (slot_no >= 0),
    CONSTRAINT quota_concurrency_leases_time_check CHECK (expires_at > acquired_at)
);

CREATE INDEX quota_concurrency_leases_expiry ON quota_concurrency_leases(expires_at);
