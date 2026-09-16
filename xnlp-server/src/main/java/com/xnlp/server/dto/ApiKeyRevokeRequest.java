package com.xnlp.server.dto;

import jakarta.validation.constraints.Size;

public record ApiKeyRevokeRequest(
        @Size(max = 255) String reason) {
}
