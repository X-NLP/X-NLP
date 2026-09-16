package com.xnlp.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.xnlp.core.config.ModelProtocol;
import com.xnlp.core.config.ModelType;

import java.time.Instant;
import java.util.List;

/** Typed provider readiness report returned by the diagnostics endpoints. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderDiagnosticResponse(
        Instant checkedAt,
        boolean activeProbe,
        List<ProviderDiagnostic> diagnostics) {

    public ProviderDiagnosticResponse {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProviderDiagnostic(
            String name,
            ModelType type,
            ModelProtocol protocol,
            String provider,
            String model,
            String endpoint,
            boolean configured,
            Boolean reachable,
            Boolean usable,
            String status,
            Integer httpStatus,
            long elapsedMillis,
            String failureCode,
            String message,
            String suggestedAction) {
    }
}
