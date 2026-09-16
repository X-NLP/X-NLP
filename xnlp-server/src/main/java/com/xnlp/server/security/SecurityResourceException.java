package com.xnlp.server.security;

public class SecurityResourceException extends RuntimeException {
    public enum Reason { API_KEY_NOT_FOUND, API_KEY_LIMIT_EXCEEDED, API_KEY_INVALID }

    private final Reason reason;

    private SecurityResourceException(Reason reason, String message) {
        super(message); this.reason = reason;
    }

    public Reason reason() { return reason; }

    public static SecurityResourceException apiKeyNotFound() {
        return new SecurityResourceException(Reason.API_KEY_NOT_FOUND, "API key was not found");
    }

    public static SecurityResourceException apiKeyLimitExceeded() {
        return new SecurityResourceException(Reason.API_KEY_LIMIT_EXCEEDED, "Tenant API key limit was exceeded");
    }

    public static SecurityResourceException apiKeyInvalid(String message) {
        return new SecurityResourceException(Reason.API_KEY_INVALID, message);
    }
}
