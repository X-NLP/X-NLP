package com.xnlp.server.service;

import com.xnlp.core.rag.ChunkPolicy;
import com.xnlp.core.rag.IngestionJob;
import com.xnlp.core.rag.KnowledgeBase;
import com.xnlp.core.rag.KnowledgeChunk;
import com.xnlp.core.rag.KnowledgeDocument;
import com.xnlp.core.rag.RagContractException;
import com.xnlp.core.rag.RagErrorCode;
import com.xnlp.core.rag.VectorRecord;
import com.xnlp.core.repository.IngestionJobRepository;
import com.xnlp.core.repository.KnowledgeBaseRepository;
import com.xnlp.core.repository.KnowledgeDocumentRepository;
import com.xnlp.core.repository.RetrievalEvaluationRepository;
import com.xnlp.server.config.KnowledgeIngestionProperties;
import com.xnlp.server.rag.DeterministicDocumentChunker;
import com.xnlp.server.rag.DocumentChunker;
import com.xnlp.server.tenant.TenantContext;
import io.micrometer.observation.annotation.Observed;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Coordinates tenant-explicit, incremental document ingestion. Provider calls
 * happen outside database transactions; successful chunks and vectors are then
 * swapped atomically by {@link KnowledgeIndexWriter}.
 */
@Service
@Profile("!memory")
public class KnowledgeIngestionService {

    private final ObjectProvider<EmbeddingModel> embeddingModels;
    private final KnowledgeBaseRepository knowledgeBases;
    private final KnowledgeDocumentRepository documents;
    private final IngestionJobRepository jobs;
    private final RetrievalEvaluationRepository retrievalEvaluations;
    private final DocumentChunker chunker;
    private final KnowledgeIndexWriter indexWriter;
    private final TaskExecutor taskExecutor;
    private final KnowledgeIngestionProperties properties;

    public KnowledgeIngestionService(
            ObjectProvider<EmbeddingModel> embeddingModels,
            KnowledgeBaseRepository knowledgeBases,
            KnowledgeDocumentRepository documents,
            IngestionJobRepository jobs,
            RetrievalEvaluationRepository retrievalEvaluations,
            DocumentChunker chunker,
            KnowledgeIndexWriter indexWriter,
            @Qualifier("ingestionTaskExecutor") TaskExecutor taskExecutor,
            KnowledgeIngestionProperties properties) {
        this.embeddingModels = embeddingModels;
        this.knowledgeBases = knowledgeBases;
        this.documents = documents;
        this.jobs = jobs;
        this.retrievalEvaluations = retrievalEvaluations;
        this.chunker = chunker;
        this.indexWriter = indexWriter;
        this.taskExecutor = taskExecutor;
        this.properties = properties;
    }

    public List<KnowledgeBase> listKnowledgeBases() {
        return knowledgeBases.findAll(TenantContext.currentTenantId());
    }

    public KnowledgeBase getKnowledgeBase(String knowledgeBaseId) {
        String tenantId = TenantContext.currentTenantId();
        return requireKnowledgeBase(tenantId, knowledgeBaseId);
    }

    public KnowledgeBase createKnowledgeBase(
            String name,
            String description,
            String embeddingModel,
            ChunkPolicy chunkPolicy) {
        String tenantId = TenantContext.currentTenantId();
        String normalizedName = requireText(name, "name");
        boolean duplicate = knowledgeBases.findAll(tenantId).stream()
                .anyMatch(existing -> existing.name().equals(normalizedName));
        if (duplicate) {
            throw new RagContractException(
                    RagErrorCode.KNOWLEDGE_BASE_CONFLICT,
                    "A knowledge base with the same name already exists",
                    Map.of("name", normalizedName));
        }
        Instant now = Instant.now();
        KnowledgeBase knowledgeBase = new KnowledgeBase(
                UUID.randomUUID().toString(), normalizedName, normalizeOptional(description),
                embeddingModel == null || embeddingModel.isBlank()
                        ? properties.getDefaultEmbeddingModel() : embeddingModel.strip(),
                chunkPolicy == null ? ChunkPolicy.defaults() : chunkPolicy,
                KnowledgeBase.Status.ACTIVE, 0, 0, now, now);
        return knowledgeBases.save(tenantId, knowledgeBase);
    }

    public KnowledgeBase updateKnowledgeBase(
            String knowledgeBaseId,
            String name,
            String description,
            String embeddingModel,
            ChunkPolicy chunkPolicy,
            boolean reindex) {
        String tenantId = TenantContext.currentTenantId();
        KnowledgeBase current = requireKnowledgeBase(tenantId, knowledgeBaseId);
        String updatedName = name == null ? current.name() : requireText(name, "name");
        boolean duplicate = knowledgeBases.findAll(tenantId).stream()
                .anyMatch(existing -> !existing.id().equals(current.id()) && existing.name().equals(updatedName));
        if (duplicate) {
            throw new RagContractException(
                    RagErrorCode.KNOWLEDGE_BASE_CONFLICT,
                    "A knowledge base with the same name already exists",
                    Map.of("name", updatedName));
        }

        String updatedModel = embeddingModel == null ? current.embeddingModel() : requireText(embeddingModel, "embeddingModel");
        ChunkPolicy updatedPolicy = chunkPolicy == null ? current.chunkPolicy() : chunkPolicy;
        boolean indexDefinitionChanged = !updatedModel.equals(current.embeddingModel())
                || !updatedPolicy.equals(current.chunkPolicy());
        if (indexDefinitionChanged && current.documentCount() > 0 && !reindex) {
            throw new RagContractException(
                    RagErrorCode.REINDEX_REQUIRED,
                    "Changing the embedding model or chunk policy requires reindex=true");
        }
        if (indexDefinitionChanged && current.documentCount() > 0) {
            requireEmbeddingModel();
            ensureNoActiveIngestion(tenantId, knowledgeBaseId);
        }

        KnowledgeBase.Status status = indexDefinitionChanged && current.documentCount() > 0
                ? KnowledgeBase.Status.REINDEXING : current.status();
        KnowledgeBase updated = knowledgeBases.save(tenantId, new KnowledgeBase(
                current.id(), updatedName, description == null ? current.description() : normalizeOptional(description),
                updatedModel, updatedPolicy, status, current.documentCount(), current.chunkCount(),
                current.createdAt(), Instant.now()));
        if (indexDefinitionChanged && current.documentCount() > 0) {
            startReindexInternal(tenantId, current.id(), List.of(), true, true);
        }
        return updated;
    }

    public void deleteKnowledgeBase(String knowledgeBaseId, boolean force) {
        String tenantId = TenantContext.currentTenantId();
        KnowledgeBase knowledgeBase = requireKnowledgeBase(tenantId, knowledgeBaseId);
        ensureNoActiveIngestion(tenantId, knowledgeBaseId);
        ensureNoActiveRetrievalEvaluation(tenantId, knowledgeBaseId);
        if (knowledgeBase.documentCount() > 0 && !force) {
            throw new RagContractException(
                    RagErrorCode.KNOWLEDGE_BASE_NOT_EMPTY,
                    "Knowledge base contains documents; use force=true to delete it");
        }
        if (!indexWriter.deleteKnowledgeBase(tenantId, knowledgeBaseId)) {
            throw notFound(RagErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "Knowledge base not found", knowledgeBaseId);
        }
    }

    public List<KnowledgeDocument> listDocuments(String knowledgeBaseId) {
        String tenantId = TenantContext.currentTenantId();
        requireKnowledgeBase(tenantId, knowledgeBaseId);
        return documents.findByKnowledgeBase(tenantId, knowledgeBaseId);
    }

    public KnowledgeDocument getDocument(String knowledgeBaseId, String documentId) {
        String tenantId = TenantContext.currentTenantId();
        requireKnowledgeBase(tenantId, knowledgeBaseId);
        return requireDocument(tenantId, knowledgeBaseId, documentId);
    }

    public DocumentSubmission createDocument(
            String knowledgeBaseId,
            String title,
            String content,
            KnowledgeDocument.SourceType sourceType,
            String sourceUri,
            String externalId,
            Map<String, Object> metadata) {
        String tenantId = TenantContext.currentTenantId();
        requireKnowledgeBase(tenantId, knowledgeBaseId);
        String checksum = DeterministicDocumentChunker.sha256(requireContent(content));
        String normalizedExternalId = normalizeOptional(externalId);
        if (normalizedExternalId != null) {
            var existing = documents.findByExternalId(tenantId, knowledgeBaseId, normalizedExternalId);
            if (existing.isPresent()) {
                if (existing.get().contentChecksum().equals(checksum)) {
                    return new DocumentSubmission(existing.get(), null, true);
                }
                throw new RagContractException(
                        RagErrorCode.DOCUMENT_CONFLICT,
                        "A document with the same externalId already exists",
                        Map.of("externalId", normalizedExternalId));
            }
        }
        requireEmbeddingModel();
        Instant now = Instant.now();
        KnowledgeDocument document = new KnowledgeDocument(
                UUID.randomUUID().toString(), knowledgeBaseId, normalizedExternalId, requireText(title, "title"),
                Objects.requireNonNull(sourceType, "sourceType must not be null"), normalizeOptional(sourceUri),
                content, checksum, 1, KnowledgeDocument.IndexStatus.PENDING, null,
                metadata == null ? Map.of() : metadata, now, now);
        KnowledgeDocument saved = indexWriter.saveDocument(tenantId, document, true);
        IngestionJob job = submitDocuments(tenantId, knowledgeBaseId, List.of(saved.id()), false, false);
        return new DocumentSubmission(saved, job, false);
    }

    public DocumentSubmission updateDocument(
            String knowledgeBaseId,
            String documentId,
            String title,
            String content,
            Map<String, Object> metadata,
            long expectedVersion) {
        String tenantId = TenantContext.currentTenantId();
        requireKnowledgeBase(tenantId, knowledgeBaseId);
        KnowledgeDocument current = requireDocument(tenantId, knowledgeBaseId, documentId);
        if (current.version() != expectedVersion) {
            throw new RagContractException(
                    RagErrorCode.DOCUMENT_VERSION_CONFLICT,
                    "Document version does not match expectedVersion",
                    Map.of("expectedVersion", expectedVersion, "actualVersion", current.version()));
        }
        String updatedContent = content == null ? current.content() : requireContent(content);
        String updatedChecksum = DeterministicDocumentChunker.sha256(updatedContent);
        boolean contentChanged = !updatedChecksum.equals(current.contentChecksum());
        boolean requiresIndexing = contentChanged
                || current.indexStatus() != KnowledgeDocument.IndexStatus.INDEXED;
        if (requiresIndexing) {
            requireEmbeddingModel();
        }
        KnowledgeDocument updated = new KnowledgeDocument(
                current.id(), current.knowledgeBaseId(), current.externalId(),
                title == null ? current.title() : requireText(title, "title"), current.sourceType(), current.sourceUri(),
                updatedContent, updatedChecksum, current.version() + 1,
                requiresIndexing ? KnowledgeDocument.IndexStatus.PENDING : current.indexStatus(),
                requiresIndexing ? null : current.errorMessage(), metadata == null ? current.metadata() : metadata,
                current.createdAt(), Instant.now());
        KnowledgeDocument saved = indexWriter.updateDocument(tenantId, updated, expectedVersion, false);
        if (!requiresIndexing) {
            return new DocumentSubmission(saved, null, true);
        }
        IngestionJob job = submitDocuments(tenantId, knowledgeBaseId, List.of(saved.id()), false, false);
        return new DocumentSubmission(saved, job, false);
    }

    public void deleteDocument(String knowledgeBaseId, String documentId) {
        String tenantId = TenantContext.currentTenantId();
        requireKnowledgeBase(tenantId, knowledgeBaseId);
        requireDocument(tenantId, knowledgeBaseId, documentId);
        if (!indexWriter.deleteDocument(tenantId, knowledgeBaseId, documentId)) {
            throw notFound(RagErrorCode.DOCUMENT_NOT_FOUND, "Document not found", documentId);
        }
    }

    public IngestionJob startReindex(String knowledgeBaseId, List<String> documentIds, boolean force) {
        String tenantId = TenantContext.currentTenantId();
        return startReindexInternal(tenantId, knowledgeBaseId, documentIds, force, true);
    }

    public IngestionJob getJob(String jobId) {
        String tenantId = TenantContext.currentTenantId();
        return jobs.findById(tenantId, requireText(jobId, "jobId"))
                .orElseThrow(() -> notFound(
                        RagErrorCode.INGESTION_JOB_NOT_FOUND, "Ingestion job not found", jobId));
    }

    public IngestionJob cancelJob(String jobId) {
        String tenantId = TenantContext.currentTenantId();
        IngestionJob current = jobs.findById(tenantId, requireText(jobId, "jobId"))
                .orElseThrow(() -> notFound(
                        RagErrorCode.INGESTION_JOB_NOT_FOUND, "Ingestion job not found", jobId));
        jobs.requestCancellation(tenantId, jobId);
        return current;
    }

    private IngestionJob startReindexInternal(
            String tenantId,
            String knowledgeBaseId,
            List<String> requestedDocumentIds,
            boolean force,
            boolean updateKnowledgeBaseStatus) {
        requireKnowledgeBase(tenantId, knowledgeBaseId);
        requireEmbeddingModel();
        ensureNoActiveIngestion(tenantId, knowledgeBaseId);

        List<KnowledgeDocument> available = documents.findByKnowledgeBase(tenantId, knowledgeBaseId);
        List<String> selectedIds = selectDocumentIds(available, requestedDocumentIds);
        if (updateKnowledgeBaseStatus) {
            indexWriter.updateKnowledgeBaseStatus(tenantId, knowledgeBaseId, KnowledgeBase.Status.REINDEXING);
        }
        return submitDocuments(tenantId, knowledgeBaseId, selectedIds, force, updateKnowledgeBaseStatus);
    }

    private List<String> selectDocumentIds(
            List<KnowledgeDocument> available,
            List<String> requestedDocumentIds) {
        if (requestedDocumentIds == null || requestedDocumentIds.isEmpty()) {
            return available.stream().map(KnowledgeDocument::id).toList();
        }
        Set<String> availableIds = available.stream()
                .map(KnowledgeDocument::id)
                .collect(java.util.stream.Collectors.toSet());
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (String requested : requestedDocumentIds) {
            String id = requireText(requested, "documentId");
            if (!availableIds.contains(id)) {
                throw notFound(RagErrorCode.DOCUMENT_NOT_FOUND, "Document not found", id);
            }
            selected.add(id);
        }
        return List.copyOf(selected);
    }

    private IngestionJob submitDocuments(
            String tenantId,
            String knowledgeBaseId,
            List<String> documentIds,
            boolean force,
            boolean updateKnowledgeBaseStatus) {
        Instant now = Instant.now();
        IngestionJob job = jobs.save(tenantId, new IngestionJob(
                UUID.randomUUID().toString(), knowledgeBaseId, IngestionJob.Status.PENDING,
                documentIds.size(), 0, 0, null, now, null));
        JobCommand command = new JobCommand(
                tenantId, job.id(), knowledgeBaseId, List.copyOf(documentIds), force, updateKnowledgeBaseStatus);
        try {
            taskExecutor.execute(() -> TenantContext.runWithTenant(tenantId, () -> runJob(command)));
        } catch (RuntimeException rejected) {
            IngestionJob failed = new IngestionJob(
                    job.id(), job.knowledgeBaseId(), IngestionJob.Status.FAILED,
                    job.totalDocuments(), 0, 0, "Ingestion executor rejected the job",
                    job.createdAt(), Instant.now());
            jobs.save(tenantId, failed);
            if (updateKnowledgeBaseStatus) {
                indexWriter.updateKnowledgeBaseStatus(tenantId, knowledgeBaseId, KnowledgeBase.Status.ERROR);
            }
            throw new IllegalStateException("Ingestion executor is unavailable", rejected);
        }
        return job;
    }

    @Observed(name = "xnlp.rag.ingestion", contextualName = "knowledge-document-ingestion")
    void runJob(JobCommand command) {
        IngestionJob initial = jobs.findById(command.tenantId(), command.jobId())
                .orElseThrow(() -> new IllegalStateException("Ingestion job disappeared before execution"));
        IngestionJob running = new IngestionJob(
                initial.id(), initial.knowledgeBaseId(), IngestionJob.Status.RUNNING,
                initial.totalDocuments(), 0, 0, null, initial.createdAt(), null);
        jobs.save(command.tenantId(), running);

        int processed = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        boolean cancelled = false;
        try {
            for (String documentId : command.documentIds()) {
                if (jobs.isCancellationRequested(command.tenantId(), command.jobId())) {
                    cancelled = true;
                    break;
                }
                try {
                    KnowledgeDocument document = requireDocument(
                            command.tenantId(), command.knowledgeBaseId(), documentId);
                    if (!command.force() && document.indexStatus() == KnowledgeDocument.IndexStatus.INDEXED) {
                        processed++;
                    } else {
                        processDocument(command, document);
                        processed++;
                    }
                } catch (IngestionCancelledException cancelledException) {
                    cancelled = true;
                    break;
                } catch (RuntimeException failure) {
                    processed++;
                    failed++;
                    errors.add(documentId + ": " + safeFailureMessage(failure));
                    markDocumentFailed(command.tenantId(), command.knowledgeBaseId(), documentId, failure);
                }
                jobs.save(command.tenantId(), new IngestionJob(
                        running.id(), running.knowledgeBaseId(), IngestionJob.Status.RUNNING,
                        running.totalDocuments(), processed, failed, summarize(errors),
                        running.createdAt(), null));
            }

            IngestionJob.Status finalStatus;
            if (cancelled) {
                finalStatus = IngestionJob.Status.CANCELLED;
            } else if (failed == 0) {
                finalStatus = IngestionJob.Status.COMPLETED;
            } else if (failed == processed && processed == running.totalDocuments()) {
                finalStatus = IngestionJob.Status.FAILED;
            } else {
                finalStatus = IngestionJob.Status.PARTIAL;
            }
            jobs.save(command.tenantId(), new IngestionJob(
                    running.id(), running.knowledgeBaseId(), finalStatus,
                    running.totalDocuments(), processed, failed, summarize(errors),
                    running.createdAt(), Instant.now()));
            reconcileKnowledgeBaseStatus(command);
        } catch (RuntimeException unexpected) {
            jobs.save(command.tenantId(), new IngestionJob(
                    running.id(), running.knowledgeBaseId(), IngestionJob.Status.FAILED,
                    running.totalDocuments(), processed, failed, "Ingestion job failed unexpectedly",
                    running.createdAt(), Instant.now()));
            reconcileKnowledgeBaseStatus(command);
        }
    }

    private void processDocument(JobCommand command, KnowledgeDocument document) {
        KnowledgeBase knowledgeBase = requireKnowledgeBase(command.tenantId(), command.knowledgeBaseId());
        KnowledgeDocument indexing = copyWithStatus(document, KnowledgeDocument.IndexStatus.INDEXING, null);
        if (!indexWriter.updateDocumentIndexStatus(
                command.tenantId(), document, KnowledgeDocument.IndexStatus.INDEXING, null)) {
            throw new RagContractException(
                    RagErrorCode.DOCUMENT_VERSION_CONFLICT,
                    "Document changed or was deleted before indexing started");
        }
        List<KnowledgeChunk> replacementChunks = chunker.chunk(indexing, knowledgeBase.chunkPolicy());
        List<VectorRecord> replacementVectors = embedChunks(command, knowledgeBase, replacementChunks);
        if (jobs.isCancellationRequested(command.tenantId(), command.jobId())) {
            indexWriter.updateDocumentIndexStatus(
                    command.tenantId(), indexing, KnowledgeDocument.IndexStatus.PENDING, null);
            throw new IngestionCancelledException();
        }
        indexWriter.replaceDocumentIndex(
                command.tenantId(), copyWithStatus(indexing, KnowledgeDocument.IndexStatus.INDEXED, null),
                replacementChunks, replacementVectors);
    }

    private List<VectorRecord> embedChunks(
            JobCommand command,
            KnowledgeBase knowledgeBase,
            List<KnowledgeChunk> replacementChunks) {
        EmbeddingModel model = requireEmbeddingModel();
        List<VectorRecord> result = new ArrayList<>(replacementChunks.size());
        Integer expectedDimensions = null;
        for (int start = 0; start < replacementChunks.size(); start += properties.getBatchSize()) {
            checkCancellation(command);
            int end = Math.min(replacementChunks.size(), start + properties.getBatchSize());
            List<KnowledgeChunk> batch = replacementChunks.subList(start, end);
            List<float[]> embeddings = embedWithRetry(
                    command, model, batch.stream().map(KnowledgeChunk::content).toList());
            if (embeddings == null || embeddings.size() != batch.size()) {
                throw new RagContractException(
                        RagErrorCode.VECTOR_DIMENSION_MISMATCH,
                        "Embedding provider returned an unexpected number of vectors");
            }
            for (int index = 0; index < batch.size(); index++) {
                KnowledgeChunk chunk = batch.get(index);
                float[] embedding = requireVector(embeddings.get(index));
                if (expectedDimensions == null) {
                    expectedDimensions = embedding.length;
                } else if (expectedDimensions != embedding.length) {
                    throw new RagContractException(
                            RagErrorCode.VECTOR_DIMENSION_MISMATCH,
                            "Embedding provider returned inconsistent vector dimensions");
                }
                result.add(new VectorRecord(
                        chunk.id(), chunk.knowledgeBaseId(), chunk.documentId(), knowledgeBase.embeddingModel(),
                        embedding.length, embedding, chunk.contentChecksum(), chunk.metadata(), Instant.now()));
            }
        }
        return List.copyOf(result);
    }

    private List<float[]> embedWithRetry(
            JobCommand command,
            EmbeddingModel model,
            List<String> texts) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt <= properties.getMaxRetries(); attempt++) {
            checkCancellation(command);
            try {
                return model.embed(texts);
            } catch (RuntimeException failure) {
                lastFailure = failure;
                if (attempt >= properties.getMaxRetries()) {
                    break;
                }
                sleepBeforeRetry(command);
            }
        }
        throw new IllegalStateException("Embedding provider request failed", lastFailure);
    }

    private void sleepBeforeRetry(JobCommand command) {
        checkCancellation(command);
        long millis = properties.getRetryBackoff().toMillis();
        if (millis == 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IngestionCancelledException();
        }
    }

    private void checkCancellation(JobCommand command) {
        if (jobs.isCancellationRequested(command.tenantId(), command.jobId())) {
            throw new IngestionCancelledException();
        }
    }

    private void markDocumentFailed(
            String tenantId,
            String knowledgeBaseId,
            String documentId,
            RuntimeException failure) {
        documents.findById(tenantId, knowledgeBaseId, documentId)
                .filter(document -> document.indexStatus() == KnowledgeDocument.IndexStatus.INDEXING)
                .ifPresent(document -> indexWriter.updateDocumentIndexStatus(
                        tenantId, document, KnowledgeDocument.IndexStatus.FAILED, safeFailureMessage(failure)));
    }

    private void ensureNoActiveRetrievalEvaluation(String tenantId, String knowledgeBaseId) {
        if (retrievalEvaluations.hasActiveRun(tenantId, knowledgeBaseId)) {
            throw new RagContractException(
                    RagErrorCode.RETRIEVAL_EVALUATION_IN_PROGRESS,
                    "A retrieval evaluation is already running for this knowledge base");
        }
    }

    private void ensureNoActiveIngestion(String tenantId, String knowledgeBaseId) {
        boolean active = jobs.findByKnowledgeBase(tenantId, knowledgeBaseId).stream()
                .anyMatch(job -> job.status() == IngestionJob.Status.PENDING
                        || job.status() == IngestionJob.Status.RUNNING);
        if (active) {
            throw new RagContractException(
                    RagErrorCode.REINDEX_IN_PROGRESS,
                    "An ingestion job is already running for this knowledge base");
        }
    }

    private void reconcileKnowledgeBaseStatus(JobCommand command) {
        KnowledgeBase knowledgeBase = requireKnowledgeBase(command.tenantId(), command.knowledgeBaseId());
        if (!command.updateKnowledgeBaseStatus() && knowledgeBase.status() == KnowledgeBase.Status.ACTIVE) {
            return;
        }
        boolean activeJobExists = jobs.findByKnowledgeBase(command.tenantId(), command.knowledgeBaseId()).stream()
                .anyMatch(job -> job.status() == IngestionJob.Status.PENDING
                        || job.status() == IngestionJob.Status.RUNNING);
        if (activeJobExists) {
            return;
        }
        boolean incompleteDocumentExists = documents.findByKnowledgeBase(
                        command.tenantId(), command.knowledgeBaseId()).stream()
                .anyMatch(document -> document.indexStatus() != KnowledgeDocument.IndexStatus.INDEXED);
        indexWriter.updateKnowledgeBaseStatus(
                command.tenantId(), command.knowledgeBaseId(),
                incompleteDocumentExists ? KnowledgeBase.Status.ERROR : KnowledgeBase.Status.ACTIVE);
    }

    private KnowledgeDocument copyWithStatus(
            KnowledgeDocument document,
            KnowledgeDocument.IndexStatus status,
            String errorMessage) {
        return new KnowledgeDocument(
                document.id(), document.knowledgeBaseId(), document.externalId(), document.title(),
                document.sourceType(), document.sourceUri(), document.content(), document.contentChecksum(),
                document.version(), status, errorMessage, document.metadata(), document.createdAt(), Instant.now());
    }

    private EmbeddingModel requireEmbeddingModel() {
        return embeddingModels.orderedStream().findFirst()
                .orElseThrow(() -> new RagContractException(
                        RagErrorCode.PROVIDER_UNCONFIGURED,
                        "No Spring AI EmbeddingModel is configured"));
    }

    private KnowledgeBase requireKnowledgeBase(String tenantId, String knowledgeBaseId) {
        String id = requireText(knowledgeBaseId, "knowledgeBaseId");
        return knowledgeBases.findById(tenantId, id)
                .orElseThrow(() -> notFound(
                        RagErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "Knowledge base not found", id));
    }

    private KnowledgeDocument requireDocument(String tenantId, String knowledgeBaseId, String documentId) {
        String id = requireText(documentId, "documentId");
        return documents.findById(tenantId, requireText(knowledgeBaseId, "knowledgeBaseId"), id)
                .orElseThrow(() -> notFound(RagErrorCode.DOCUMENT_NOT_FOUND, "Document not found", id));
    }

    private RagContractException notFound(RagErrorCode code, String message, String id) {
        return new RagContractException(code, message, Map.of("id", String.valueOf(id)));
    }

    private float[] requireVector(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new RagContractException(
                    RagErrorCode.VECTOR_DIMENSION_MISMATCH,
                    "Embedding provider returned an empty vector");
        }
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new RagContractException(
                        RagErrorCode.VECTOR_DIMENSION_MISMATCH,
                        "Embedding provider returned a non-finite vector");
            }
        }
        return vector.clone();
    }

    private String safeFailureMessage(RuntimeException failure) {
        if (failure instanceof RagContractException) {
            return truncate(failure.getMessage(), 500);
        }
        return "Embedding provider request failed";
    }

    private String summarize(List<String> errors) {
        if (errors == null || errors.isEmpty()) {
            return null;
        }
        return truncate(String.join("; ", errors.stream().limit(5).toList()), 2_000);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String requireContent(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

    public record DocumentSubmission(KnowledgeDocument document, IngestionJob job, boolean unchanged) {
    }

    record JobCommand(
            String tenantId,
            String jobId,
            String knowledgeBaseId,
            List<String> documentIds,
            boolean force,
            boolean updateKnowledgeBaseStatus) {
    }

    private static final class IngestionCancelledException extends RuntimeException {
        private IngestionCancelledException() {
            super("Ingestion was cancelled");
        }
    }
}
