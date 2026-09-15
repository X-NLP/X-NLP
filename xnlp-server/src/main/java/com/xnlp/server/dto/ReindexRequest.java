package com.xnlp.server.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/** Request for rebuilding all or selected documents in a knowledge base. */
public record ReindexRequest(
        @Size(max = 1_000, message = "documentIds must not contain more than 1000 values")
        List<@jakarta.validation.constraints.NotBlank(message = "documentId must not be blank") String> documentIds,
        Boolean force) {

    public ReindexRequest {
        documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
        if (force == null) {
            force = false;
        }
    }
}
