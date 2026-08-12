-- Portable bootstrap schema. Switch DB with SPRING_PROFILES_ACTIVE=mysql|postgres|h2.
CREATE TABLE IF NOT EXISTS model_config (
    name VARCHAR(190) PRIMARY KEY,
    type VARCHAR(64) NOT NULL,
    protocol VARCHAR(128) NOT NULL,
    source VARCHAR(64) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model_name VARCHAR(190) NOT NULL,
    base_url VARCHAR(512),
    api_key TEXT,
    version VARCHAR(64),
    model_path VARCHAR(512),
    backend VARCHAR(64),
    device VARCHAR(64),
    max_input_length INTEGER,
    max_output_length INTEGER,
    options_json TEXT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE TABLE IF NOT EXISTS datasets (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(190) NOT NULL,
    description TEXT,
    task_type VARCHAR(64),
    entry_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE TABLE IF NOT EXISTS dataset_entries (
    id VARCHAR(64) PRIMARY KEY,
    dataset_id VARCHAR(64) NOT NULL,
    seq INTEGER NOT NULL,
    input_text TEXT NOT NULL,
    expected_output TEXT,
    labels_json TEXT,
    metadata_json TEXT
);
CREATE TABLE IF NOT EXISTS evaluation_runs (
    id VARCHAR(64) PRIMARY KEY,
    model_name VARCHAR(190) NOT NULL,
    dataset_id VARCHAR(64) NOT NULL,
    dataset_name VARCHAR(190),
    task_type VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    error_message TEXT,
    metrics_json TEXT,
    created_at TIMESTAMP,
    completed_at TIMESTAMP,
    elapsed_seconds DOUBLE PRECISION,
    total_entries INTEGER NOT NULL DEFAULT 0,
    processed_entries INTEGER NOT NULL DEFAULT 0,
    progress_percent DOUBLE PRECISION NOT NULL DEFAULT 0,
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE
);
