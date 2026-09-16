package com.xnlp.server.controller;

import com.xnlp.core.rag.RetrievalResult;
import com.xnlp.server.dto.KnowledgeSearchRequest;
import com.xnlp.server.service.RetrievalService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP adapter for persistent knowledge-base retrieval and optional reranking. */
@RestController
@RequestMapping("/api/v1/knowledge-bases")
@Validated
@Profile("!memory")
public class RetrievalController {

    private final RetrievalService retrievalService;

    public RetrievalController(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @PostMapping("/{knowledgeBaseId}/search")
    public RetrievalResult search(
            @PathVariable @NotBlank String knowledgeBaseId,
            @Valid @RequestBody KnowledgeSearchRequest request) {
        return retrievalService.search(
                knowledgeBaseId,
                request.query(),
                request.topK(),
                request.minScore(),
                request.filter(),
                Boolean.TRUE.equals(request.rerank()),
                request.rerankTopN());
    }
}
