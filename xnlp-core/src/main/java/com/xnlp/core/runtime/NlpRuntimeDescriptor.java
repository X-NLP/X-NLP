package com.xnlp.core.runtime;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable identity and capability declaration for a runtime model. */
public record NlpRuntimeDescriptor(
        String name,
        String provider,
        String modelVersion,
        String modelSha256,
        Set<String> capabilities) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-fA-F]{64}");

    public NlpRuntimeDescriptor {
        name = requireText(name, "name");
        provider = requireText(provider, "provider");
        modelVersion = requireText(modelVersion, "modelVersion");
        if (modelSha256 == null || !SHA_256.matcher(modelSha256).matches()) {
            throw new IllegalArgumentException("modelSha256 must be a 64-character hexadecimal SHA-256");
        }
        modelSha256 = modelSha256.toLowerCase(Locale.ROOT);
        if (capabilities == null || capabilities.isEmpty()) {
            throw new IllegalArgumentException("capabilities must not be empty");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String capability : capabilities) {
            normalized.add(requireText(capability, "capability").toUpperCase(Locale.ROOT));
        }
        capabilities = Set.copyOf(normalized);
    }

    public boolean supports(String capability) {
        return capability != null && capabilities.contains(capability.toUpperCase(Locale.ROOT));
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
