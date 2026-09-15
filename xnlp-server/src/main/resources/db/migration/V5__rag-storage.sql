-- Portable knowledge and vector storage. __LARGE_TEXT__ is resolved by the migration runner.
CREATE TABLE IF NOT EXISTS knowledge_bases (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(64) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description TEXT,
    embedding_model VARCHAR(190) NOT NULL,
    chunk_max_characters INTEGER NOT NULL,
    chunk_overlap_characters INTEGER NOT NULL,
    chunk_separator_mode VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    document_count BIGINT NOT NULL DEFAULT 0,
    chunk_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, id)
);

CREATE TABLE IF NOT EXISTS knowledge_documents (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(64) NOT NULL,
    knowledge_base_id VARCHAR(64) NOT NULL,
    external_id VARCHAR(190),
    title VARCHAR(500) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_uri VARCHAR(2048),
    content_text __LARGE_TEXT__ NOT NULL,
    content_checksum VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL,
    index_status VARCHAR(32) NOT NULL,
    error_message TEXT,
    metadata_json TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, id)
);

CREATE TABLE IF NOT EXISTS knowledge_chunks (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(64) NOT NULL,
    knowledge_base_id VARCHAR(64) NOT NULL,
    document_id VARCHAR(64) NOT NULL,
    seq INTEGER NOT NULL,
    content_text __LARGE_TEXT__ NOT NULL,
    content_checksum VARCHAR(64) NOT NULL,
    start_offset INTEGER NOT NULL,
    end_offset INTEGER NOT NULL,
    metadata_json TEXT,
    PRIMARY KEY (tenant_id, id)
);

CREATE TABLE IF NOT EXISTS knowledge_embeddings (
    tenant_id VARCHAR(64) NOT NULL,
    chunk_id VARCHAR(64) NOT NULL,
    knowledge_base_id VARCHAR(64) NOT NULL,
    document_id VARCHAR(64) NOT NULL,
    embedding_model VARCHAR(190) NOT NULL,
    dimensions INTEGER NOT NULL,
    embedding_json __LARGE_TEXT__ NOT NULL,
    content_checksum VARCHAR(64) NOT NULL,
    metadata_json TEXT,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, chunk_id, embedding_model)
);

CREATE TABLE IF NOT EXISTS ingestion_jobs (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(64) NOT NULL,
    knowledge_base_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    total_documents INTEGER NOT NULL DEFAULT 0,
    processed_documents INTEGER NOT NULL DEFAULT 0,
    failed_documents INTEGER NOT NULL DEFAULT 0,
    error_summary TEXT,
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    PRIMARY KEY (tenant_id, id)
);
