CREATE TABLE IF NOT EXISTS versioned_datasets (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(64) NOT NULL,
    name VARCHAR(190) NOT NULL,
    description TEXT,
    task_type VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    entry_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT versioned_datasets_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT versioned_datasets_version_check CHECK (version >= 0),
    CONSTRAINT versioned_datasets_entry_count_check CHECK (entry_count >= 0)
);

CREATE INDEX versioned_datasets_updated ON versioned_datasets(tenant_id, updated_at);

CREATE TABLE IF NOT EXISTS versioned_dataset_entries (
    tenant_id VARCHAR(64) NOT NULL,
    dataset_id VARCHAR(64) NOT NULL,
    id VARCHAR(64) NOT NULL,
    seq INTEGER NOT NULL,
    input_text __LARGE_TEXT__ NOT NULL,
    expected_output __LARGE_TEXT__,
    labels_json __LARGE_TEXT__,
    metadata_json __LARGE_TEXT__,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, dataset_id, id),
    CONSTRAINT versioned_dataset_entries_dataset_fk FOREIGN KEY (tenant_id, dataset_id)
        REFERENCES versioned_datasets(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT versioned_dataset_entries_seq_check CHECK (seq >= 0)
);

CREATE INDEX versioned_dataset_entries_order ON versioned_dataset_entries(tenant_id, dataset_id, seq);

CREATE TABLE IF NOT EXISTS dataset_version_snapshots (
    tenant_id VARCHAR(64) NOT NULL,
    dataset_id VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL,
    name VARCHAR(190) NOT NULL,
    description TEXT,
    task_type VARCHAR(64),
    entry_count INTEGER NOT NULL,
    entries_json __LARGE_TEXT__ NOT NULL,
    created_by VARCHAR(190) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, dataset_id, version),
    CONSTRAINT dataset_version_snapshots_dataset_fk FOREIGN KEY (tenant_id, dataset_id)
        REFERENCES versioned_datasets(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT dataset_version_snapshots_version_check CHECK (version >= 0),
    CONSTRAINT dataset_version_snapshots_entry_count_check CHECK (entry_count >= 0)
);

CREATE TABLE IF NOT EXISTS dataset_import_jobs (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(64) NOT NULL,
    dataset_id VARCHAR(64) NOT NULL,
    source_name VARCHAR(512) NOT NULL,
    status VARCHAR(32) NOT NULL,
    total_rows INTEGER NOT NULL,
    imported_rows INTEGER NOT NULL DEFAULT 0,
    failed_rows INTEGER NOT NULL DEFAULT 0,
    expected_version BIGINT NOT NULL,
    resulting_version BIGINT,
    error_summary TEXT,
    created_by VARCHAR(190) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT dataset_import_jobs_dataset_fk FOREIGN KEY (tenant_id, dataset_id)
        REFERENCES versioned_datasets(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT dataset_import_jobs_total_check CHECK (total_rows >= 0),
    CONSTRAINT dataset_import_jobs_imported_check CHECK (imported_rows >= 0),
    CONSTRAINT dataset_import_jobs_failed_check CHECK (failed_rows >= 0),
    CONSTRAINT dataset_import_jobs_expected_version_check CHECK (expected_version >= 0),
    CONSTRAINT dataset_import_jobs_resulting_version_check CHECK (resulting_version IS NULL OR resulting_version >= 0)
);

CREATE INDEX dataset_import_jobs_status ON dataset_import_jobs(tenant_id, dataset_id, status, created_at);

CREATE TABLE IF NOT EXISTS dataset_import_errors (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(64) NOT NULL,
    job_id VARCHAR(64) NOT NULL,
    row_number INTEGER NOT NULL,
    error_code VARCHAR(96) NOT NULL,
    error_message TEXT NOT NULL,
    raw_record __LARGE_TEXT__,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT dataset_import_errors_job_fk FOREIGN KEY (tenant_id, job_id)
        REFERENCES dataset_import_jobs(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT dataset_import_errors_row_check CHECK (row_number > 0)
);

CREATE INDEX dataset_import_errors_row ON dataset_import_errors(tenant_id, job_id, row_number);
