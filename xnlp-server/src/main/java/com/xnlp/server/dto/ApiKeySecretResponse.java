package com.xnlp.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/** One-time credential response returned only by create and rotate operations. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiKeySecretResponse(
        ApiKeyResponse apiKey,
        String secret,
        Instant previousKeyValidUntil) {
}
