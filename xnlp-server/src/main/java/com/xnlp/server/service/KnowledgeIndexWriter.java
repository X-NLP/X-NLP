package com.xnlp.server.service;

import com.xnlp.core.rag.KnowledgeBase;
import com.xnlp.core.rag.KnowledgeChunk;
import com.xnlp.core.rag.KnowledgeDocument;
import com.xnlp.core.rag.KnowledgeVectorStore;
import com.xnlp.core.rag.VectorRecord;
import com.xnlp.core.rag.RagContractException;
import com.xnlp.core.rag.RagErrorCode;
import com.xnlp.core.repository.IngestionJobRepository;
import com.xnlp.core.repository.KnowledgeBaseRepository;
import com.xnlp.core.repository.KnowledgeChunkRepository;
import com.xnlp.core.repository.KnowledgeDocumentRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Transaction boundary for document metadata, chunks and vectors. */
@Component
@Profile("!memory")
public class KnowledgeIndexWriter {

    private final KnowledgeBaseRepository knowledgeBases;
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeChunkRepository chunks;
    private final KnowledgeVectorStore vectors;
    private final IngestionJobRepository jobs;

    public KnowledgeIndexWriter(
            KnowledgeBaseRepository knowledgeBases,
            KnowledgeDocumentRepository documents,
            KnowledgeChunkRepository chunks,
            KnowledgeVectorStore vectors,
            IngestionJobRepository jobs) {
        this.knowledgeBases = knowledgeBases;
        this.documents = documents;
        this.chunks = chunks;
        this.vectors = vectors;
        this.jobs = jobs;
    }

    @Transactional
    public KnowledgeDocument saveDocument(String tenantId, KnowledgeDocument document, boolean refreshStatistics) {
        KnowledgeDocument saved = documents.save(tenantId, document);
        if (refreshStatistics) {
            refreshStatistics(tenantId, document.knowledgeBaseId());
        }
        return saved;
    }

    @Transactional
    public KnowledgeDocument updateDocument(
            String tenantId,
            KnowledgeDocument document,
            long expectedVersion,
            boolean refreshStatistics) {
        if (!documents.update(tenantId, document, expectedVersion)) {
            throw new RagContractException(
                    RagErrorCode.DOCUMENT_VERSION_CONFLICT,
                    "Document version does not match expectedVersion");
        }
        if (refreshStatistics) {
            refreshStatistics(tenantId, document.knowledgeBaseId());
        }
        return document;
    }

    @Transactional
    public KnowledgeDocument replaceDocumentIndex(
            String tenantId,
            KnowledgeDocument indexedDocument,
            List<KnowledgeChunk> replacementChunks,
            List<VectorRecord> replacementVectors) {
        boolean updated = documents.updateIndexStatus(
                tenantId, indexedDocument.knowledgeBaseId(), indexedDocument.id(), indexedDocument.version(),
                KnowledgeDocument.IndexStatus.INDEXED, null, indexedDocument.updatedAt());
        if (!updated) {
            throw new RagContractException(
                    RagErrorCode.DOCUMENT_VERSION_CONFLICT,
                    "Document changed or was deleted while its index was being built");
        }
        vectors.deleteByDocument(tenantId, indexedDocument.knowledgeBaseId(), indexedDocument.id());
        chunks.replaceByDocument(
                tenantId, indexedDocument.knowledgeBaseId(), indexedDocument.id(), replacementChunks);
        vectors.upsert(tenantId, replacementVectors);
        refreshStatistics(tenantId, indexedDocument.knowledgeBaseId());
        return indexedDocument;
    }

    @Transactional
    public boolean updateDocumentIndexStatus(
            String tenantId,
            KnowledgeDocument document,
            KnowledgeDocument.IndexStatus status,
            String errorMessage) {
        return documents.updateIndexStatus(
                tenantId, document.knowledgeBaseId(), document.id(), document.version(),
                status, errorMessage, Instant.now());
    }

    @Transactional
    public boolean deleteDocument(String tenantId, String knowledgeBaseId, String documentId) {
        vectors.deleteByDocument(tenantId, knowledgeBaseId, documentId);
        chunks.deleteByDocument(tenantId, knowledgeBaseId, documentId);
        boolean deleted = documents.deleteById(tenantId, knowledgeBaseId, documentId);
        if (deleted) {
            refreshStatistics(tenantId, knowledgeBaseId);
        }
        return deleted;
    }

    @Transactional
    public boolean deleteKnowledgeBase(String tenantId, String knowledgeBaseId) {
        List<KnowledgeDocument> existingDocuments = documents.findByKnowledgeBase(tenantId, knowledgeBaseId);
        vectors.deleteByKnowledgeBase(tenantId, knowledgeBaseId);
        for (KnowledgeDocument document : existingDocuments) {
            chunks.deleteByDocument(tenantId, knowledgeBaseId, document.id());
            documents.deleteById(tenantId, knowledgeBaseId, document.id());
        }
        jobs.deleteByKnowledgeBase(tenantId, knowledgeBaseId);
        return knowledgeBases.deleteById(tenantId, knowledgeBaseId);
    }

    @Transactional
    public KnowledgeBase updateKnowledgeBaseStatus(
            String tenantId,
            String knowledgeBaseId,
            KnowledgeBase.Status status) {
        KnowledgeBase current = knowledgeBases.findById(tenantId, knowledgeBaseId)
                .orElseThrow(() -> new IllegalStateException("Knowledge base disappeared during ingestion"));
        return knowledgeBases.save(tenantId, new KnowledgeBase(
                current.id(), current.name(), current.description(), current.embeddingModel(),
                current.chunkPolicy(), status, current.documentCount(), current.chunkCount(),
                current.createdAt(), Instant.now()));
    }

    private void refreshStatistics(String tenantId, String knowledgeBaseId) {
        knowledgeBases.findById(tenantId, knowledgeBaseId).ifPresent(current ->
                knowledgeBases.save(tenantId, new KnowledgeBase(
                        current.id(), current.name(), current.description(), current.embeddingModel(),
                        current.chunkPolicy(), current.status(),
                        documents.countByKnowledgeBase(tenantId, knowledgeBaseId),
                        chunks.countByKnowledgeBase(tenantId, knowledgeBaseId),
                        current.createdAt(), Instant.now())));
    }
}
