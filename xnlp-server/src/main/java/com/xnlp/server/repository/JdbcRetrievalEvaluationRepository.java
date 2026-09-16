package com.xnlp.server.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xnlp.core.rag.RetrievalMatch;
import com.xnlp.core.rag.eval.RetrievalEvaluationMetrics;
import com.xnlp.core.rag.eval.RetrievalEvaluationRun;
import com.xnlp.core.rag.eval.RetrievalEvaluationSampleResult;
import com.xnlp.core.repository.RetrievalEvaluationRepository;
import com.xnlp.server.tenant.TenantContext;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Portable JdbcTemplate adapter for durable retrieval evaluation evidence. */
@Repository
@Profile("!memory")
public class JdbcRetrievalEvaluationRepository implements RetrievalEvaluationRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<RetrievalMatch>> MATCH_LIST_TYPE = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcRetrievalEvaluationRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public List<RetrievalEvaluationRun> findRuns(String tenantId, String knowledgeBaseId) {
        return jdbc.query("""
                SELECT * FROM retrieval_evaluation_runs
                WHERE tenant_id = ? AND knowledge_base_id = ?
                ORDER BY created_at DESC, id
                """, this::mapRun, tenant(tenantId), requireText(knowledgeBaseId, "knowledgeBaseId"));
    }

    @Override
    public Optional<RetrievalEvaluationRun> findRun(String tenantId, String knowledgeBaseId, String runId) {
        return jdbc.query("""
                SELECT * FROM retrieval_evaluation_runs
                WHERE tenant_id = ? AND knowledge_base_id = ? AND id = ?
                """, this::mapRun, tenant(tenantId), requireText(knowledgeBaseId, "knowledgeBaseId"),
                requireText(runId, "runId")).stream().findFirst();
    }

    @Override
    public boolean hasActiveRun(String tenantId, String knowledgeBaseId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM retrieval_evaluation_runs
                WHERE tenant_id = ? AND knowledge_base_id = ? AND status IN ('QUEUED', 'RUNNING')
                """, Integer.class, tenant(tenantId), requireText(knowledgeBaseId, "knowledgeBaseId"));
        return count != null && count > 0;
    }

    @Override
    public RetrievalEvaluationRun saveRun(String tenantId, RetrievalEvaluationRun run) {
        String tenant = tenant(tenantId);
        Object[] updateArguments = runUpdateArguments(tenant, run);
        int updated = jdbc.update("""
                UPDATE retrieval_evaluation_runs SET dataset_id = ?, status = ?, top_k = ?, min_score = ?,
                    filter_json = ?, rerank = ?, rerank_top_n = ?, total_samples = ?, processed_samples = ?,
                    recall_at_k = ?, mrr = ?, ndcg_at_k = ?, average_latency_ms = ?, p95_latency_ms = ?,
                    error_message = ?, completed_at = ?
                WHERE tenant_id = ? AND knowledge_base_id = ? AND id = ?
                """, updateArguments);
        if (updated == 0) {
            try {
                RetrievalEvaluationMetrics metrics = run.metrics();
                jdbc.update("""
                        INSERT INTO retrieval_evaluation_runs
                            (tenant_id, id, knowledge_base_id, dataset_id, status, top_k, min_score,
                             filter_json, rerank, rerank_top_n, total_samples, processed_samples,
                             recall_at_k, mrr, ndcg_at_k, average_latency_ms, p95_latency_ms,
                             error_message, created_at, completed_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, tenant, run.id(), run.knowledgeBaseId(), run.datasetId(), run.status().name(),
                        run.topK(), run.minScore(), toJson(run.filter()), run.rerank(), run.rerankTopN(),
                        run.totalSamples(), run.processedSamples(), metric(metrics, Metric.RECALL),
                        metric(metrics, Metric.MRR), metric(metrics, Metric.NDCG),
                        metric(metrics, Metric.AVERAGE_LATENCY), longMetric(metrics), run.errorMessage(),
                        Timestamp.from(run.createdAt()), timestamp(run.completedAt()));
            } catch (DuplicateKeyException race) {
                jdbc.update("""
                        UPDATE retrieval_evaluation_runs SET dataset_id = ?, status = ?, top_k = ?, min_score = ?,
                            filter_json = ?, rerank = ?, rerank_top_n = ?, total_samples = ?, processed_samples = ?,
                            recall_at_k = ?, mrr = ?, ndcg_at_k = ?, average_latency_ms = ?, p95_latency_ms = ?,
                            error_message = ?, completed_at = ?
                        WHERE tenant_id = ? AND knowledge_base_id = ? AND id = ?
                        """, updateArguments);
            }
        }
        return run;
    }

    @Override
    public RetrievalEvaluationSampleResult saveSampleResult(
            String tenantId,
            String knowledgeBaseId,
            RetrievalEvaluationSampleResult result,
            int sequence) {
        String tenant = tenant(tenantId);
        String knowledgeBase = requireText(knowledgeBaseId, "knowledgeBaseId");
        Object[] updateArguments = sampleUpdateArguments(tenant, knowledgeBase, result, sequence);
        int updated = jdbc.update("""
                UPDATE retrieval_evaluation_samples SET seq = ?, sample_id = ?, query_text = ?,
                    relevant_chunk_ids_json = ?, raw_matches_json = ?, final_matches_json = ?,
                    recall_at_k = ?, reciprocal_rank = ?, ndcg_at_k = ?, latency_ms = ?, miss = ?,
                    rerank_changed = ?, trace_id = ?
                WHERE tenant_id = ? AND knowledge_base_id = ? AND run_id = ? AND id = ?
                """, updateArguments);
        if (updated == 0) {
            try {
                jdbc.update("""
                        INSERT INTO retrieval_evaluation_samples
                            (tenant_id, id, knowledge_base_id, run_id, seq, sample_id, query_text,
                             relevant_chunk_ids_json, raw_matches_json, final_matches_json,
                             recall_at_k, reciprocal_rank, ndcg_at_k, latency_ms, miss,
                             rerank_changed, trace_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, tenant, result.id(), knowledgeBase, result.runId(), sequence, result.sampleId(),
                        result.query(), toJson(result.relevantChunkIds()), toJson(result.rawMatches()),
                        toJson(result.finalMatches()), result.recallAtK(), result.reciprocalRank(),
                        result.ndcgAtK(), result.latencyMs(), result.miss(), result.rerankChanged(), result.traceId());
            } catch (DuplicateKeyException race) {
                jdbc.update("""
                        UPDATE retrieval_evaluation_samples SET seq = ?, sample_id = ?, query_text = ?,
                            relevant_chunk_ids_json = ?, raw_matches_json = ?, final_matches_json = ?,
                            recall_at_k = ?, reciprocal_rank = ?, ndcg_at_k = ?, latency_ms = ?, miss = ?,
                            rerank_changed = ?, trace_id = ?
                        WHERE tenant_id = ? AND knowledge_base_id = ? AND run_id = ? AND id = ?
                        """, updateArguments);
            }
        }
        return result;
    }

    @Override
    public List<RetrievalEvaluationSampleResult> findSampleResults(
            String tenantId,
            String knowledgeBaseId,
            String runId) {
        return jdbc.query("""
                SELECT * FROM retrieval_evaluation_samples
                WHERE tenant_id = ? AND knowledge_base_id = ? AND run_id = ?
                ORDER BY seq, id
                """, this::mapSample, tenant(tenantId), requireText(knowledgeBaseId, "knowledgeBaseId"),
                requireText(runId, "runId"));
    }

    @Override
    @Transactional
    public void deleteByKnowledgeBase(String tenantId, String knowledgeBaseId) {
        String tenant = tenant(tenantId);
        String knowledgeBase = requireText(knowledgeBaseId, "knowledgeBaseId");
        jdbc.update("DELETE FROM retrieval_evaluation_samples WHERE tenant_id = ? AND knowledge_base_id = ?",
                tenant, knowledgeBase);
        jdbc.update("DELETE FROM retrieval_evaluation_runs WHERE tenant_id = ? AND knowledge_base_id = ?",
                tenant, knowledgeBase);
    }

    private RetrievalEvaluationRun mapRun(ResultSet rs, int rowNum) throws SQLException {
        RetrievalEvaluationMetrics metrics = nullableDouble(rs, "recall_at_k") == null ? null
                : new RetrievalEvaluationMetrics(
                        rs.getDouble("recall_at_k"), rs.getDouble("mrr"), rs.getDouble("ndcg_at_k"),
                        rs.getDouble("average_latency_ms"), rs.getLong("p95_latency_ms"));
        return new RetrievalEvaluationRun(
                rs.getString("id"), rs.getString("knowledge_base_id"), rs.getString("dataset_id"),
                RetrievalEvaluationRun.Status.valueOf(rs.getString("status")), rs.getInt("top_k"),
                nullableDouble(rs, "min_score"), fromJson(rs.getString("filter_json"), MAP_TYPE, Map.of()),
                rs.getBoolean("rerank"), rs.getInt("rerank_top_n"), rs.getInt("total_samples"),
                rs.getInt("processed_samples"), metrics, rs.getString("error_message"),
                toInstant(rs.getTimestamp("created_at")), toInstant(rs.getTimestamp("completed_at")));
    }

    private RetrievalEvaluationSampleResult mapSample(ResultSet rs, int rowNum) throws SQLException {
        return new RetrievalEvaluationSampleResult(
                rs.getString("id"), rs.getString("run_id"), rs.getString("sample_id"),
                rs.getString("query_text"),
                fromJson(rs.getString("relevant_chunk_ids_json"), STRING_LIST_TYPE, List.of()),
                fromJson(rs.getString("raw_matches_json"), MATCH_LIST_TYPE, List.of()),
                fromJson(rs.getString("final_matches_json"), MATCH_LIST_TYPE, List.of()),
                rs.getDouble("recall_at_k"), rs.getDouble("reciprocal_rank"), rs.getDouble("ndcg_at_k"),
                rs.getLong("latency_ms"), rs.getBoolean("miss"), rs.getBoolean("rerank_changed"),
                rs.getString("trace_id"));
    }

    private Object[] runUpdateArguments(String tenantId, RetrievalEvaluationRun run) {
        RetrievalEvaluationMetrics metrics = run.metrics();
        return new Object[]{
                run.datasetId(), run.status().name(), run.topK(), run.minScore(), toJson(run.filter()),
                run.rerank(), run.rerankTopN(), run.totalSamples(), run.processedSamples(),
                metric(metrics, Metric.RECALL), metric(metrics, Metric.MRR), metric(metrics, Metric.NDCG),
                metric(metrics, Metric.AVERAGE_LATENCY), longMetric(metrics), run.errorMessage(),
                timestamp(run.completedAt()), tenantId, run.knowledgeBaseId(), run.id()
        };
    }

    private Object[] sampleUpdateArguments(
            String tenantId,
            String knowledgeBaseId,
            RetrievalEvaluationSampleResult result,
            int sequence) {
        return new Object[]{
                sequence, result.sampleId(), result.query(), toJson(result.relevantChunkIds()),
                toJson(result.rawMatches()), toJson(result.finalMatches()), result.recallAtK(),
                result.reciprocalRank(), result.ndcgAtK(), result.latencyMs(), result.miss(),
                result.rerankChanged(), result.traceId(), tenantId, knowledgeBaseId, result.runId(), result.id()
        };
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Retrieval evaluation data cannot be serialized", ex);
        }
    }

    private <T> T fromJson(String value, TypeReference<T> type, T empty) throws SQLException {
        if (value == null || value.isBlank()) {
            return empty;
        }
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException ex) {
            throw new SQLException("Invalid retrieval evaluation JSON", ex);
        }
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static Double metric(RetrievalEvaluationMetrics metrics, Metric metric) {
        if (metrics == null) {
            return null;
        }
        return switch (metric) {
            case RECALL -> metrics.recallAtK();
            case MRR -> metrics.mrr();
            case NDCG -> metrics.ndcgAtK();
            case AVERAGE_LATENCY -> metrics.averageLatencyMs();
        };
    }

    private static Long longMetric(RetrievalEvaluationMetrics metrics) {
        return metrics == null ? null : metrics.p95LatencyMs();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static String tenant(String tenantId) {
        return TenantContext.normalize(requireText(tenantId, "tenantId"));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

    private enum Metric {
        RECALL,
        MRR,
        NDCG,
        AVERAGE_LATENCY
    }
}
