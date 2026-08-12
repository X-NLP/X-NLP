package com.xnlp.core.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Execution context passed to an {@link NlpComponent}.
 *
 * <p>Carries the input text, optional pair text (for similarity tasks),
 * language hint, and an open parameter map for task-specific options
 * such as coarse flag, topK, labels, or style.
 */
public class NlpContext {

    private final String text;
    private final String textPair;
    private final String language;
    private final Map<String, Object> parameters;

    public NlpContext(String text, String textPair, String language,
                      Map<String, Object> parameters) {
        this.text = text != null ? text : "";
        this.textPair = textPair != null ? textPair : "";
        this.language = language != null ? language : "zh";
        this.parameters = parameters != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(parameters))
                : Collections.emptyMap();
    }

    public String getText() { return text; }
    public String getTextPair() { return textPair; }
    public String getLanguage() { return language; }

    public boolean hasParameter(String key) { return parameters.containsKey(key); }
    public Object getParameter(String key) { return parameters.get(key); }
    public Map<String, Object> getParameters() { return parameters; }

    public boolean getBool(String key) {
        return parameters.get(key) instanceof Boolean b && b;
    }
    public int getInt(String key, int fallback) {
        return parameters.get(key) instanceof Number n ? n.intValue() : fallback;
    }
    public String getString(String key, String fallback) {
        Object value = parameters.get(key);
        return value != null ? value.toString() : fallback;
    }

    /** Convenience builder. */
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String text = "";
        private String textPair = "";
        private String language = "zh";
        private final Map<String, Object> params = new LinkedHashMap<>();

        public Builder text(String value) { this.text = value; return this; }
        public Builder textPair(String value) { this.textPair = value; return this; }
        public Builder language(String value) { this.language = value; return this; }
        public Builder param(String key, Object value) { params.put(key, value); return this; }
        public Builder params(Map<String, Object> values) { params.putAll(values); return this; }
        public NlpContext build() { return new NlpContext(text, textPair, language, params); }
    }
}
