CREATE TABLE IF NOT EXISTS evaluation_recovery_runs (
    tenant_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    dataset_id VARCHAR(64) NOT NULL,
    dataset_version BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    root_run_id VARCHAR(64) NOT NULL,
    parent_run_id VARCHAR(64),
    attempt_no INTEGER NOT NULL,
    retry_failed_only BOOLEAN NOT NULL DEFAULT FALSE,
    total_samples INTEGER NOT NULL,
    actor_id VARCHAR(190) NOT NULL,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    PRIMARY KEY (tenant_id, run_id),
    CONSTRAINT evaluation_recovery_runs_tenant_fk FOREIGN KEY (tenant_id)
        REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT evaluation_recovery_runs_dataset_version_check CHECK (dataset_version >= 0),
    CONSTRAINT evaluation_recovery_runs_attempt_check CHECK (attempt_no >= 0),
    CONSTRAINT evaluation_recovery_runs_total_check CHECK (total_samples >= 0),
    CONSTRAINT evaluation_recovery_runs_lineage_check CHECK (
        (attempt_no = 0 AND parent_run_id IS NULL AND root_run_id = run_id AND retry_failed_only = FALSE)
        OR (attempt_no > 0 AND parent_run_id IS NOT NULL AND parent_run_id <> run_id)
    )
);

CREATE INDEX evaluation_recovery_runs_lineage
    ON evaluation_recovery_runs(tenant_id, root_run_id, attempt_no);
CREATE INDEX evaluation_recovery_runs_status
    ON evaluation_recovery_runs(tenant_id, status, updated_at);

CREATE TABLE IF NOT EXISTS evaluation_sample_results (
    tenant_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    sample_id VARCHAR(64) NOT NULL,
    sample_sequence INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    expected_output __LARGE_TEXT__,
    actual_output __LARGE_TEXT__,
    score_json __LARGE_TEXT__,
    error_code VARCHAR(96),
    error_message TEXT,
    idempotency_key VARCHAR(190) NOT NULL,
    completed_at TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, run_id, sample_id),
    CONSTRAINT evaluation_sample_results_run_fk FOREIGN KEY (tenant_id, run_id)
        REFERENCES evaluation_recovery_runs(tenant_id, run_id) ON DELETE CASCADE,
    CONSTRAINT evaluation_sample_results_sequence_check CHECK (sample_sequence >= 0),
    CONSTRAINT evaluation_sample_results_idempotency_unique
        UNIQUE (tenant_id, run_id, idempotency_key)
);

CREATE INDEX evaluation_sample_results_order
    ON evaluation_sample_results(tenant_id, run_id, sample_sequence);
CREATE INDEX evaluation_sample_results_failed
    ON evaluation_sample_results(tenant_id, run_id, status, sample_sequence);

CREATE TABLE IF NOT EXISTS evaluation_checkpoints (
    tenant_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    next_sample_sequence INTEGER NOT NULL DEFAULT 0,
    processed_samples INTEGER NOT NULL DEFAULT 0,
    succeeded_samples INTEGER NOT NULL DEFAULT 0,
    failed_samples INTEGER NOT NULL DEFAULT 0,
    last_sample_id VARCHAR(64),
    fencing_token BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, run_id),
    CONSTRAINT evaluation_checkpoints_run_fk FOREIGN KEY (tenant_id, run_id)
        REFERENCES evaluation_recovery_runs(tenant_id, run_id) ON DELETE CASCADE,
    CONSTRAINT evaluation_checkpoints_next_check CHECK (next_sample_sequence >= 0),
    CONSTRAINT evaluation_checkpoints_processed_check CHECK (processed_samples >= 0),
    CONSTRAINT evaluation_checkpoints_succeeded_check CHECK (succeeded_samples >= 0),
    CONSTRAINT evaluation_checkpoints_failed_check CHECK (failed_samples >= 0),
    CONSTRAINT evaluation_checkpoints_count_check CHECK (processed_samples = succeeded_samples + failed_samples),
    CONSTRAINT evaluation_checkpoints_fencing_check CHECK (fencing_token >= 0)
);

CREATE TABLE IF NOT EXISTS evaluation_recovery_leases (
    tenant_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    owner_id VARCHAR(190),
    fencing_token BIGINT NOT NULL DEFAULT 0,
    acquired_at TIMESTAMP,
    renewed_at TIMESTAMP,
    expires_at TIMESTAMP,
    PRIMARY KEY (tenant_id, run_id),
    CONSTRAINT evaluation_recovery_leases_run_fk FOREIGN KEY (tenant_id, run_id)
        REFERENCES evaluation_recovery_runs(tenant_id, run_id) ON DELETE CASCADE,
    CONSTRAINT evaluation_recovery_leases_fencing_check CHECK (fencing_token >= 0),
    CONSTRAINT evaluation_recovery_leases_holder_check CHECK (
        (owner_id IS NULL AND acquired_at IS NULL AND renewed_at IS NULL AND expires_at IS NULL)
        OR (owner_id IS NOT NULL AND acquired_at IS NOT NULL AND renewed_at IS NOT NULL AND expires_at IS NOT NULL)
    )
);

CREATE INDEX evaluation_recovery_leases_expiry
    ON evaluation_recovery_leases(expires_at);
