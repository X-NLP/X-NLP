package com.xnlp.server.service;

import com.xnlp.core.eval.EvaluationDataset;
import com.xnlp.core.eval.EvaluationEntry;
import com.xnlp.core.rag.KnowledgeBase;
import com.xnlp.core.rag.RagContractException;
import com.xnlp.core.rag.RagErrorCode;
import com.xnlp.core.rag.RetrievalResult;
import com.xnlp.core.rag.eval.RetrievalEvaluationCalculator;
import com.xnlp.core.rag.eval.RetrievalEvaluationMetrics;
import com.xnlp.core.rag.eval.RetrievalEvaluationRun;
import com.xnlp.core.rag.eval.RetrievalEvaluationSampleResult;
import com.xnlp.core.repository.KnowledgeBaseRepository;
import com.xnlp.core.repository.RetrievalEvaluationRepository;
import com.xnlp.server.dto.RetrievalEvaluationCreateRequest;
import com.xnlp.server.dto.RetrievalEvaluationSampleRequest;
import com.xnlp.server.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/** Schedules tenant-scoped retrieval quality evaluations and persists sample evidence. */
@Service
@Profile("!memory")
public class RetrievalEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalEvaluationService.class);

    private final KnowledgeBaseRepository knowledgeBases;
    private final DatasetService datasets;
    private final RetrievalService retrievalService;
    private final RetrievalEvaluationRepository repository;
    private final TaskExecutor taskExecutor;

    public RetrievalEvaluationService(
            KnowledgeBaseRepository knowledgeBases,
            DatasetService datasets,
            RetrievalService retrievalService,
            RetrievalEvaluationRepository repository,
            @Qualifier("evaluationTaskExecutor") TaskExecutor taskExecutor) {
        this.knowledgeBases = knowledgeBases;
        this.datasets = datasets;
        this.retrievalService = retrievalService;
        this.repository = repository;
        this.taskExecutor = taskExecutor;
    }

    public RetrievalEvaluationRun start(
            String knowledgeBaseId,
            RetrievalEvaluationCreateRequest request) {
        String tenantId = TenantContext.currentTenantId();
        KnowledgeBase knowledgeBase = requireKnowledgeBase(tenantId, knowledgeBaseId);
        List<InputSample> samples = request.datasetId() == null || request.datasetId().isBlank()
                ? requestSamples(request.samples())
                : datasetSamples(request.datasetId());
        Instant now = Instant.now();
        RetrievalEvaluationRun queued = new RetrievalEvaluationRun(
                UUID.randomUUID().toString(), knowledgeBase.id(), normalizeOptional(request.datasetId()),
                RetrievalEvaluationRun.Status.QUEUED, request.topK(), request.minScore(), request.filter(),
                Boolean.TRUE.equals(request.rerank()), request.rerankTopN(), samples.size(), 0,
                null, null, now, null);
        repository.saveRun(tenantId, queued);
        try {
            taskExecutor.execute(() -> TenantContext.runWithTenant(
                    tenantId, () -> execute(tenantId, queued, samples)));
        } catch (RuntimeException rejected) {
            RetrievalEvaluationRun failed = terminalRun(
                    queued, RetrievalEvaluationRun.Status.FAILED, 0, null,
                    "Retrieval evaluation queue is unavailable");
            repository.saveRun(tenantId, failed);
            log.warn("Retrieval evaluation queue rejected run {}", queued.id());
            return failed;
        }
        return queued;
    }

    public List<RetrievalEvaluationRun> list(String knowledgeBaseId) {
        String tenantId = TenantContext.currentTenantId();
        requireKnowledgeBase(tenantId, knowledgeBaseId);
        return repository.findRuns(tenantId, knowledgeBaseId);
    }

    public RetrievalEvaluationRun get(String knowledgeBaseId, String runId) {
        String tenantId = TenantContext.currentTenantId();
        requireKnowledgeBase(tenantId, knowledgeBaseId);
        return repository.findRun(tenantId, knowledgeBaseId, runId)
                .orElseThrow(() -> new NoSuchElementException("Retrieval evaluation run not found: " + runId));
    }

    public List<RetrievalEvaluationSampleResult> samples(String knowledgeBaseId, String runId) {
        String tenantId = TenantContext.currentTenantId();
        get(knowledgeBaseId, runId);
        return repository.findSampleResults(tenantId, knowledgeBaseId, runId);
    }

    private void execute(String tenantId, RetrievalEvaluationRun queued, List<InputSample> samples) {
        RetrievalEvaluationRun running = copyRun(
                queued, RetrievalEvaluationRun.Status.RUNNING, 0, null, null, null);
        repository.saveRun(tenantId, running);
        List<RetrievalEvaluationSampleResult> results = new ArrayList<>();
        try {
            for (int index = 0; index < samples.size(); index++) {
                InputSample sample = samples.get(index);
                long started = System.nanoTime();
                RetrievalResult raw = retrievalService.search(
                        queued.knowledgeBaseId(), sample.query(), queued.topK(), queued.minScore(),
                        queued.filter(), false, queued.rerankTopN());
                RetrievalResult result = queued.rerank()
                        ? retrievalService.search(
                                queued.knowledgeBaseId(), sample.query(), queued.topK(), queued.minScore(),
                                queued.filter(), true, queued.rerankTopN())
                        : raw;
                long latencyMs = Math.max(0, (System.nanoTime() - started) / 1_000_000L);
                RetrievalEvaluationCalculator.SampleMetrics metrics = RetrievalEvaluationCalculator.evaluate(
                        sample.relevantChunkIds(), raw.matches(), result.matches(), queued.topK());
                RetrievalEvaluationSampleResult sampleResult = new RetrievalEvaluationSampleResult(
                        UUID.randomUUID().toString(), queued.id(), sample.id(), sample.query(),
                        sample.relevantChunkIds(), raw.matches(), result.matches(), metrics.recallAtK(),
                        metrics.reciprocalRank(), metrics.ndcgAtK(), latencyMs, metrics.miss(),
                        metrics.rerankChanged(), result.traceId() == null ? raw.traceId() : result.traceId());
                repository.saveSampleResult(tenantId, queued.knowledgeBaseId(), sampleResult, index);
                results.add(sampleResult);
                repository.saveRun(tenantId, copyRun(
                        queued, RetrievalEvaluationRun.Status.RUNNING, results.size(), null, null, null));
            }
            RetrievalEvaluationMetrics aggregate = RetrievalEvaluationCalculator.aggregate(results);
            repository.saveRun(tenantId, terminalRun(
                    queued, RetrievalEvaluationRun.Status.COMPLETED, results.size(), aggregate, null));
        } catch (RuntimeException failure) {
            repository.saveRun(tenantId, terminalRun(
                    queued, RetrievalEvaluationRun.Status.FAILED, results.size(), null,
                    "Retrieval evaluation failed"));
            log.warn("Retrieval evaluation run {} failed: {}", queued.id(), failure.getClass().getSimpleName());
        }
    }

    private KnowledgeBase requireKnowledgeBase(String tenantId, String knowledgeBaseId) {
        String id = requireText(knowledgeBaseId, "knowledgeBaseId");
        return knowledgeBases.findById(tenantId, id)
                .orElseThrow(() -> new RagContractException(
                        RagErrorCode.KNOWLEDGE_BASE_NOT_FOUND,
                        "Knowledge base was not found",
                        Map.of("knowledgeBaseId", id)));
    }

    private List<InputSample> requestSamples(List<RetrievalEvaluationSampleRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("samples must not be empty");
        }
        List<InputSample> result = new ArrayList<>(requests.size());
        for (int index = 0; index < requests.size(); index++) {
            RetrievalEvaluationSampleRequest sample = requests.get(index);
            String id = sample.id() == null || sample.id().isBlank()
                    ? "sample-" + (index + 1) : sample.id().strip();
            result.add(new InputSample(id, requireText(sample.query(), "query"),
                    relevantIds(sample.relevantChunkIds())));
        }
        return List.copyOf(result);
    }

    private List<InputSample> datasetSamples(String datasetId) {
        EvaluationDataset dataset = datasets.get(requireText(datasetId, "datasetId"))
                .orElseThrow(() -> new NoSuchElementException("Dataset not found: " + datasetId));
        if (dataset.getEntries() == null || dataset.getEntries().isEmpty()) {
            throw new IllegalArgumentException("Retrieval evaluation dataset must contain entries");
        }
        List<InputSample> result = new ArrayList<>(dataset.getEntries().size());
        for (int index = 0; index < dataset.getEntries().size(); index++) {
            EvaluationEntry entry = dataset.getEntries().get(index);
            Object labels = entry.getLabels() == null ? null : entry.getLabels().get("relevantChunkIds");
            if (!(labels instanceof Collection<?> values)) {
                throw new IllegalArgumentException(
                        "Dataset entry labels.relevantChunkIds must be a non-empty array");
            }
            List<String> relevant = values.stream()
                    .map(value -> value instanceof String text ? text : null)
                    .toList();
            String sampleId = entry.getId() == null || entry.getId().isBlank()
                    ? "entry-" + (index + 1) : entry.getId().strip();
            result.add(new InputSample(sampleId, requireText(entry.getInput(), "entry input"),
                    relevantIds(relevant)));
        }
        return List.copyOf(result);
    }

    private static List<String> relevantIds(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("relevantChunkIds must not be empty");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            normalized.add(requireText(value, "relevantChunkId"));
        }
        return List.copyOf(normalized);
    }

    private static RetrievalEvaluationRun terminalRun(
            RetrievalEvaluationRun source,
            RetrievalEvaluationRun.Status status,
            int processed,
            RetrievalEvaluationMetrics metrics,
            String errorMessage) {
        return copyRun(source, status, processed, metrics, errorMessage, Instant.now());
    }

    private static RetrievalEvaluationRun copyRun(
            RetrievalEvaluationRun source,
            RetrievalEvaluationRun.Status status,
            int processed,
            RetrievalEvaluationMetrics metrics,
            String errorMessage,
            Instant completedAt) {
        return new RetrievalEvaluationRun(
                source.id(), source.knowledgeBaseId(), source.datasetId(), status, source.topK(),
                source.minScore(), source.filter(), source.rerank(), source.rerankTopN(),
                source.totalSamples(), processed, metrics, errorMessage, source.createdAt(), completedAt);
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

    private record InputSample(String id, String query, List<String> relevantChunkIds) {
    }
}
