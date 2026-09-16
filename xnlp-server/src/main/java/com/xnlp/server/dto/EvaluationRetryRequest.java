package com.xnlp.server.dto;

/** Request contract for retrying a terminal evaluation run. */
public record EvaluationRetryRequest(boolean failedOnly) {
}
