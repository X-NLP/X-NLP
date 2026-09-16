package com.xnlp.server.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record ApiKeyRotateRequest(
        @Min(0) @Max(86400) long gracePeriodSeconds) {
}
