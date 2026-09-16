CREATE TABLE IF NOT EXISTS pipeline_definitions (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(64) NOT NULL,
    name VARCHAR(190) NOT NULL,
    description TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(190) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT pipeline_definitions_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT pipeline_definitions_version_check CHECK (version >= 0)
);

CREATE INDEX pipeline_definitions_updated ON pipeline_definitions(tenant_id, updated_at);

CREATE TABLE IF NOT EXISTS pipeline_definition_versions (
    tenant_id VARCHAR(64) NOT NULL,
    pipeline_id VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL,
    name VARCHAR(190) NOT NULL,
    description TEXT,
    created_by VARCHAR(190) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, pipeline_id, version),
    CONSTRAINT pipeline_definition_versions_pipeline_fk FOREIGN KEY (tenant_id, pipeline_id)
        REFERENCES pipeline_definitions(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT pipeline_definition_versions_version_check CHECK (version >= 0)
);

CREATE TABLE IF NOT EXISTS pipeline_nodes (
    tenant_id VARCHAR(64) NOT NULL,
    pipeline_id VARCHAR(64) NOT NULL,
    pipeline_version BIGINT NOT NULL,
    node_id VARCHAR(96) NOT NULL,
    capability_id VARCHAR(190) NOT NULL,
    config_json __LARGE_TEXT__,
    timeout_ms BIGINT NOT NULL,
    max_attempts INTEGER NOT NULL,
    PRIMARY KEY (tenant_id, pipeline_id, pipeline_version, node_id),
    CONSTRAINT pipeline_nodes_version_fk FOREIGN KEY (tenant_id, pipeline_id, pipeline_version)
        REFERENCES pipeline_definition_versions(tenant_id, pipeline_id, version) ON DELETE CASCADE,
    CONSTRAINT pipeline_nodes_timeout_check CHECK (timeout_ms > 0),
    CONSTRAINT pipeline_nodes_attempts_check CHECK (max_attempts > 0)
);

CREATE TABLE IF NOT EXISTS pipeline_edges (
    tenant_id VARCHAR(64) NOT NULL,
    pipeline_id VARCHAR(64) NOT NULL,
    pipeline_version BIGINT NOT NULL,
    source_node_id VARCHAR(96) NOT NULL,
    target_node_id VARCHAR(96) NOT NULL,
    source_output VARCHAR(190) NOT NULL DEFAULT '',
    target_input VARCHAR(190) NOT NULL DEFAULT '',
    PRIMARY KEY (tenant_id, pipeline_id, pipeline_version, source_node_id, target_node_id, source_output, target_input),
    CONSTRAINT pipeline_edges_version_fk FOREIGN KEY (tenant_id, pipeline_id, pipeline_version)
        REFERENCES pipeline_definition_versions(tenant_id, pipeline_id, version) ON DELETE CASCADE,
    CONSTRAINT pipeline_edges_source_fk FOREIGN KEY (tenant_id, pipeline_id, pipeline_version, source_node_id)
        REFERENCES pipeline_nodes(tenant_id, pipeline_id, pipeline_version, node_id) ON DELETE CASCADE,
    CONSTRAINT pipeline_edges_target_fk FOREIGN KEY (tenant_id, pipeline_id, pipeline_version, target_node_id)
        REFERENCES pipeline_nodes(tenant_id, pipeline_id, pipeline_version, node_id) ON DELETE CASCADE,
    CONSTRAINT pipeline_edges_self_check CHECK (source_node_id <> target_node_id)
);

CREATE INDEX pipeline_edges_target ON pipeline_edges(tenant_id, pipeline_id, pipeline_version, target_node_id);

CREATE TABLE IF NOT EXISTS pipeline_runs (
    tenant_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    pipeline_id VARCHAR(64) NOT NULL,
    pipeline_version BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    input_json __LARGE_TEXT__,
    output_json __LARGE_TEXT__,
    error_code VARCHAR(96),
    error_message TEXT,
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 0,
    actor_id VARCHAR(190) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP,
    updated_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    PRIMARY KEY (tenant_id, run_id),
    CONSTRAINT pipeline_runs_version_fk FOREIGN KEY (tenant_id, pipeline_id, pipeline_version)
        REFERENCES pipeline_definition_versions(tenant_id, pipeline_id, version),
    CONSTRAINT pipeline_runs_revision_check CHECK (revision >= 0)
);

CREATE INDEX pipeline_runs_status ON pipeline_runs(tenant_id, status, updated_at);
CREATE INDEX pipeline_runs_pipeline ON pipeline_runs(tenant_id, pipeline_id, created_at);

CREATE TABLE IF NOT EXISTS pipeline_node_attempts (
    tenant_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    node_id VARCHAR(96) NOT NULL,
    attempt_no INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    input_json __LARGE_TEXT__,
    output_json __LARGE_TEXT__,
    error_code VARCHAR(96),
    error_message TEXT,
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    PRIMARY KEY (tenant_id, run_id, node_id, attempt_no),
    CONSTRAINT pipeline_node_attempts_run_fk FOREIGN KEY (tenant_id, run_id)
        REFERENCES pipeline_runs(tenant_id, run_id) ON DELETE CASCADE,
    CONSTRAINT pipeline_node_attempts_attempt_check CHECK (attempt_no > 0)
);

CREATE INDEX pipeline_node_attempts_order ON pipeline_node_attempts(tenant_id, run_id, node_id, attempt_no);

CREATE TABLE IF NOT EXISTS pipeline_run_event_sequences (
    tenant_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    next_sequence BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, run_id),
    CONSTRAINT pipeline_run_event_sequences_run_fk FOREIGN KEY (tenant_id, run_id)
        REFERENCES pipeline_runs(tenant_id, run_id) ON DELETE CASCADE,
    CONSTRAINT pipeline_run_event_sequences_next_check CHECK (next_sequence > 0)
);

CREATE TABLE IF NOT EXISTS pipeline_run_events (
    tenant_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    event_sequence BIGINT NOT NULL,
    event_type VARCHAR(96) NOT NULL,
    node_id VARCHAR(96),
    attempt_no INTEGER,
    payload_json __LARGE_TEXT__,
    occurred_at TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, run_id, event_sequence),
    CONSTRAINT pipeline_run_events_run_fk FOREIGN KEY (tenant_id, run_id)
        REFERENCES pipeline_runs(tenant_id, run_id) ON DELETE CASCADE,
    CONSTRAINT pipeline_run_events_sequence_check CHECK (event_sequence > 0),
    CONSTRAINT pipeline_run_events_attempt_check CHECK (attempt_no IS NULL OR attempt_no > 0)
);
