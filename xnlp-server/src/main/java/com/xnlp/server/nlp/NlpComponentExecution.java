package com.xnlp.server.nlp;

import com.xnlp.core.api.ComponentResult;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Component output plus runtime diagnostics used by the REST facade. */
public record NlpComponentExecution(ComponentResult result, Map<String, Object> runtime) {

    public NlpComponentExecution {
        result = Objects.requireNonNull(result, "result must not be null");
        runtime = runtime == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(runtime));
    }
}
