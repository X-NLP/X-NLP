package com.xnlp.core.rag;

/** Stable RAG error codes mapped to HTTP status by the server adapter. */
public enum RagErrorCode {
    KNOWLEDGE_BASE_NOT_FOUND("knowledge_base_not_found", Kind.NOT_FOUND),
    DOCUMENT_NOT_FOUND("document_not_found", Kind.NOT_FOUND),
    INGESTION_JOB_NOT_FOUND("ingestion_job_not_found", Kind.NOT_FOUND),
    KNOWLEDGE_BASE_CONFLICT("knowledge_base_conflict", Kind.CONFLICT),
    DOCUMENT_CONFLICT("document_conflict", Kind.CONFLICT),
    DOCUMENT_VERSION_CONFLICT("document_version_conflict", Kind.CONFLICT),
    KNOWLEDGE_BASE_NOT_EMPTY("knowledge_base_not_empty", Kind.CONFLICT),
    REINDEX_REQUIRED("reindex_required", Kind.CONFLICT),
    REINDEX_IN_PROGRESS("reindex_in_progress", Kind.CONFLICT),
    RETRIEVAL_EVALUATION_IN_PROGRESS("retrieval_evaluation_in_progress", Kind.CONFLICT),
    CONTENT_TOO_LARGE("content_too_large", Kind.INVALID_REQUEST),
    VECTOR_DIMENSION_MISMATCH("vector_dimension_mismatch", Kind.UNPROCESSABLE),
    VECTOR_STORE_CAPACITY_EXCEEDED("vector_store_capacity_exceeded", Kind.UNAVAILABLE),
    PROVIDER_UNCONFIGURED("provider_unconfigured", Kind.UNAVAILABLE),
    RERANKER_UNAVAILABLE("reranker_unavailable", Kind.UNAVAILABLE),
    INSUFFICIENT_CONTEXT("insufficient_context", Kind.UNPROCESSABLE),
    MODEL_TIMEOUT("model_timeout", Kind.TIMEOUT);

    private final String code;
    private final Kind kind;

    RagErrorCode(String code, Kind kind) {
        this.code = code;
        this.kind = kind;
    }

    public String code() {
        return code;
    }

    public Kind kind() {
        return kind;
    }

    public enum Kind {
        NOT_FOUND,
        CONFLICT,
        INVALID_REQUEST,
        UNPROCESSABLE,
        UNAVAILABLE,
        TIMEOUT
    }
}
