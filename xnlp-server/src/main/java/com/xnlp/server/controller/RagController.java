package com.xnlp.server.controller;

import com.xnlp.core.rag.RagAnswer;
import com.xnlp.server.dto.RagRequest;
import com.xnlp.server.service.RagChatService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP adapter for grounded knowledge-base chat with validated citations. */
@RestController
@RequestMapping("/api/v1/knowledge-bases")
@Validated
@Profile("!memory")
public class RagController {

    private final RagChatService ragChatService;

    public RagController(RagChatService ragChatService) {
        this.ragChatService = ragChatService;
    }

    @PostMapping("/{knowledgeBaseId}/rag")
    public RagAnswer chat(
            @PathVariable @NotBlank String knowledgeBaseId,
            @Valid @RequestBody RagRequest request) {
        return ragChatService.chat(knowledgeBaseId, request);
    }
}
