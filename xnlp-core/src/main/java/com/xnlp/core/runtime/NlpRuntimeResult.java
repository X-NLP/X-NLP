package com.xnlp.core.runtime;

import com.xnlp.core.api.ComponentResult;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Component output plus provider-neutral execution diagnostics. */
public record NlpRuntimeResult(ComponentResult result, Map<String, Object> metadata) {

    public NlpRuntimeResult {
        result = Objects.requireNonNull(result, "result must not be null");
        metadata = metadata == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
