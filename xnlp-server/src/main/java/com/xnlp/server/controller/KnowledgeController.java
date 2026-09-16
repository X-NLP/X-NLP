package com.xnlp.server.controller;

import com.xnlp.core.rag.IngestionJob;
import com.xnlp.core.rag.KnowledgeBase;
import com.xnlp.core.rag.KnowledgeDocument;
import com.xnlp.server.dto.KnowledgeBaseCreateRequest;
import com.xnlp.server.dto.KnowledgeBaseUpdateRequest;
import com.xnlp.server.dto.KnowledgeDocumentCreateRequest;
import com.xnlp.server.dto.KnowledgeDocumentUpdateRequest;
import com.xnlp.server.dto.PageResponse;
import com.xnlp.server.dto.ReindexRequest;
import com.xnlp.server.service.KnowledgeIngestionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/** HTTP contracts for knowledge-base, document and ingestion lifecycle APIs. */
@RestController
@RequestMapping("/api/v1")
@Validated
@Profile("!memory")
public class KnowledgeController {

    private final KnowledgeIngestionService ingestionService;

    public KnowledgeController(KnowledgeIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/knowledge-bases")
    public ResponseEntity<KnowledgeBase> createKnowledgeBase(
            @Valid @RequestBody KnowledgeBaseCreateRequest request) {
        KnowledgeBase created = ingestionService.createKnowledgeBase(
                request.name(), request.description(), request.embeddingModel(), request.chunkPolicy().toModel());
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, "/api/v1/knowledge-bases/" + created.id())
                .body(created);
    }

    @GetMapping("/knowledge-bases")
    public PageResponse<KnowledgeBase> listKnowledgeBases(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size,
            @RequestParam(required = false) String query) {
        List<KnowledgeBase> filtered = ingestionService.listKnowledgeBases().stream()
                .filter(knowledgeBase -> matchesQuery(knowledgeBase, query))
                .toList();
        return page(filtered, page, size);
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}")
    public KnowledgeBase getKnowledgeBase(
            @PathVariable @NotBlank String knowledgeBaseId) {
        return ingestionService.getKnowledgeBase(knowledgeBaseId);
    }

    @PutMapping("/knowledge-bases/{knowledgeBaseId}")
    public KnowledgeBase updateKnowledgeBase(
            @PathVariable @NotBlank String knowledgeBaseId,
            @Valid @RequestBody KnowledgeBaseUpdateRequest request) {
        return ingestionService.updateKnowledgeBase(
                knowledgeBaseId, request.name(), request.description(), request.embeddingModel(),
                request.chunkPolicy() == null ? null : request.chunkPolicy().toModel(),
                Boolean.TRUE.equals(request.reindex()));
    }

    @DeleteMapping("/knowledge-bases/{knowledgeBaseId}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteKnowledgeBase(
            @PathVariable @NotBlank String knowledgeBaseId,
            @RequestParam(defaultValue = "false") boolean force) {
        ingestionService.deleteKnowledgeBase(knowledgeBaseId, force);
    }

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/documents")
    public ResponseEntity<KnowledgeDocument> createDocument(
            @PathVariable @NotBlank String knowledgeBaseId,
            @Valid @RequestBody KnowledgeDocumentCreateRequest request) {
        var submission = ingestionService.createDocument(
                knowledgeBaseId, request.title(), request.content(), request.sourceType(),
                request.sourceUri(), request.externalId(), request.metadata());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(
                submission.unchanged() ? HttpStatus.OK : HttpStatus.ACCEPTED);
        if (submission.job() != null) {
            response.header(HttpHeaders.LOCATION, "/api/v1/ingestion-jobs/" + submission.job().id());
        }
        return response.body(submission.document());
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/documents")
    public PageResponse<KnowledgeDocument> listDocuments(
            @PathVariable @NotBlank String knowledgeBaseId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size,
            @RequestParam(required = false) KnowledgeDocument.IndexStatus status) {
        List<KnowledgeDocument> filtered = ingestionService.listDocuments(knowledgeBaseId).stream()
                .filter(document -> status == null || document.indexStatus() == status)
                .toList();
        return page(filtered, page, size);
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/documents/{documentId}")
    public KnowledgeDocument getDocument(
            @PathVariable @NotBlank String knowledgeBaseId,
            @PathVariable @NotBlank String documentId) {
        return ingestionService.getDocument(knowledgeBaseId, documentId);
    }

    @PutMapping("/knowledge-bases/{knowledgeBaseId}/documents/{documentId}")
    public ResponseEntity<KnowledgeDocument> updateDocument(
            @PathVariable @NotBlank String knowledgeBaseId,
            @PathVariable @NotBlank String documentId,
            @Valid @RequestBody KnowledgeDocumentUpdateRequest request) {
        var submission = ingestionService.updateDocument(
                knowledgeBaseId, documentId, request.title(), request.content(),
                request.metadata(), request.expectedVersion());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(
                submission.unchanged() ? HttpStatus.OK : HttpStatus.ACCEPTED);
        if (submission.job() != null) {
            response.header(HttpHeaders.LOCATION, "/api/v1/ingestion-jobs/" + submission.job().id());
        }
        return response.body(submission.document());
    }

    @DeleteMapping("/knowledge-bases/{knowledgeBaseId}/documents/{documentId}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(
            @PathVariable @NotBlank String knowledgeBaseId,
            @PathVariable @NotBlank String documentId) {
        ingestionService.deleteDocument(knowledgeBaseId, documentId);
    }

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/reindex")
    public ResponseEntity<IngestionJob> reindex(
            @PathVariable @NotBlank String knowledgeBaseId,
            @Valid @RequestBody ReindexRequest request) {
        IngestionJob job = ingestionService.startReindex(
                knowledgeBaseId, request.documentIds(), Boolean.TRUE.equals(request.force()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.LOCATION, "/api/v1/ingestion-jobs/" + job.id())
                .body(job);
    }

    @GetMapping("/ingestion-jobs/{jobId}")
    public IngestionJob getJob(@PathVariable @NotBlank String jobId) {
        return ingestionService.getJob(jobId);
    }

    @PostMapping("/ingestion-jobs/{jobId}/cancel")
    public IngestionJob cancelJob(@PathVariable @NotBlank String jobId) {
        return ingestionService.cancelJob(jobId);
    }

    private boolean matchesQuery(KnowledgeBase knowledgeBase, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String normalized = query.strip().toLowerCase(Locale.ROOT);
        return knowledgeBase.name().toLowerCase(Locale.ROOT).contains(normalized)
                || knowledgeBase.description() != null
                && knowledgeBase.description().toLowerCase(Locale.ROOT).contains(normalized);
    }

    private <T> PageResponse<T> page(List<T> values, int page, int size) {
        long requestedOffset = (long) page * size;
        int from = (int) Math.min(values.size(), requestedOffset);
        int to = (int) Math.min(values.size(), (long) from + size);
        return PageResponse.of(values.subList(from, to), page, size, values.size());
    }
}
