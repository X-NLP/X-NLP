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

-- Construction waste vehicle access management (append-only business records).
CREATE TABLE IF NOT EXISTS waste_vehicles (
    id VARCHAR(64) PRIMARY KEY,
    plate_no VARCHAR(32) NOT NULL UNIQUE,
    vehicle_type VARCHAR(64) NOT NULL,
    company_name VARCHAR(190) NOT NULL,
    driver_name VARCHAR(64) NOT NULL,
    driver_phone VARCHAR(32) NOT NULL,
    verified BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS waste_applications (
    id VARCHAR(64) PRIMARY KEY,
    application_no VARCHAR(64) NOT NULL UNIQUE,
    waste_type VARCHAR(64) NOT NULL,
    clear_reason VARCHAR(255) NOT NULL,
    pickup_location VARCHAR(255) NOT NULL,
    estimated_weight_tons DOUBLE NOT NULL,
    vehicle_id VARCHAR(64) NOT NULL,
    processing_site VARCHAR(190) NOT NULL,
    route_description VARCHAR(255),
    order_subject VARCHAR(32) NOT NULL,
    subject_name VARCHAR(190) NOT NULL,
    contact_name VARCHAR(64) NOT NULL,
    contact_phone VARCHAR(32) NOT NULL,
    photo_urls TEXT,
    status VARCHAR(32) NOT NULL,
    remaining_weight_tons DOUBLE NOT NULL DEFAULT 0,
    reviewer VARCHAR(64),
    review_comment VARCHAR(255),
    reviewed_at TIMESTAMP,
    approved_at TIMESTAMP,
    code VARCHAR(16),
    code_expires_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS waste_audits (
    id VARCHAR(64) PRIMARY KEY,
    application_id VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    description VARCHAR(255) NOT NULL,
    operator_name VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS waste_weighings (
    id VARCHAR(64) PRIMARY KEY,
    application_id VARCHAR(64) NOT NULL,
    application_no VARCHAR(64) NOT NULL,
    plate_no VARCHAR(32) NOT NULL,
    event_type VARCHAR(16) NOT NULL,
    trip_no INTEGER NOT NULL DEFAULT 1,
    gross_weight DOUBLE NOT NULL,
    tare_weight DOUBLE NOT NULL,
    net_weight DOUBLE NOT NULL,
    weighbridge_no VARCHAR(64),
    operator_name VARCHAR(64),
    weighed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    source VARCHAR(32) NOT NULL
);
