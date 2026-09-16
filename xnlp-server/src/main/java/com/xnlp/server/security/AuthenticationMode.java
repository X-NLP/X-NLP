package com.xnlp.server.security;

public enum AuthenticationMode {
    DISABLED,
    API_KEY,
    JWT,
    HYBRID;

    public boolean acceptsApiKey() {
        return this == API_KEY || this == HYBRID;
    }

    public boolean acceptsJwt() {
        return this == JWT || this == HYBRID;
    }
}
