-- Portable retrieval evaluation storage. __LARGE_TEXT__ is resolved by the migration runner.
CREATE TABLE IF NOT EXISTS retrieval_evaluation_runs (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(64) NOT NULL,
    knowledge_base_id VARCHAR(64) NOT NULL,
    dataset_id VARCHAR(120),
    status VARCHAR(32) NOT NULL,
    top_k INTEGER NOT NULL,
    min_score DOUBLE PRECISION,
    filter_json TEXT,
    rerank BOOLEAN NOT NULL,
    rerank_top_n INTEGER NOT NULL,
    total_samples INTEGER NOT NULL,
    processed_samples INTEGER NOT NULL,
    recall_at_k DOUBLE PRECISION,
    mrr DOUBLE PRECISION,
    ndcg_at_k DOUBLE PRECISION,
    average_latency_ms DOUBLE PRECISION,
    p95_latency_ms BIGINT,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT retrieval_evaluation_runs_kb_fk
        FOREIGN KEY (tenant_id, knowledge_base_id)
        REFERENCES knowledge_bases (tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS retrieval_evaluation_samples (
    tenant_id VARCHAR(64) NOT NULL,
    id VARCHAR(64) NOT NULL,
    knowledge_base_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    seq INTEGER NOT NULL,
    sample_id VARCHAR(120) NOT NULL,
    query_text __LARGE_TEXT__ NOT NULL,
    relevant_chunk_ids_json __LARGE_TEXT__ NOT NULL,
    raw_matches_json __LARGE_TEXT__ NOT NULL,
    final_matches_json __LARGE_TEXT__ NOT NULL,
    recall_at_k DOUBLE PRECISION NOT NULL,
    reciprocal_rank DOUBLE PRECISION NOT NULL,
    ndcg_at_k DOUBLE PRECISION NOT NULL,
    latency_ms BIGINT NOT NULL,
    miss BOOLEAN NOT NULL,
    rerank_changed BOOLEAN NOT NULL,
    trace_id VARCHAR(128),
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT retrieval_evaluation_samples_run_fk
        FOREIGN KEY (tenant_id, run_id)
        REFERENCES retrieval_evaluation_runs (tenant_id, id) ON DELETE CASCADE
);
