package com.xnlp.core.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Standardized result produced by an {@link NlpComponent#execute(NlpContext)} call.
 *
 * <p>Results are structured as a string-keyed map whose shape is defined
 * per component.  Common keys include {@code tokens}, {@code entities},
 * {@code arcs}, {@code summary}, {@code label}, and {@code score}.
 */
public class ComponentResult {

    private final String componentId;
    private final Map<String, Object> data;

    public ComponentResult(String componentId, Map<String, Object> data) {
        this.componentId = componentId;
        this.data = data != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(data))
                : Collections.emptyMap();
    }

    public String getComponentId() { return componentId; }
    public Map<String, Object> getData() { return data; }

    public Object get(String key) { return data.get(key); }
    public boolean has(String key) { return data.containsKey(key); }

    @Override
    public String toString() {
        return "ComponentResult{" + "componentId='" + componentId + "', data=" + data + '}';
    }

    public static ComponentResult of(String id, Map<String, Object> data) {
        return new ComponentResult(id, data);
    }

    public static ComponentResult of(String id, String key, Object value) {
        return new ComponentResult(id, Map.of(key, value));
    }
}
