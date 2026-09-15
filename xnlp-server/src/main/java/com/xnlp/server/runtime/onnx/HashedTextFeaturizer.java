package com.xnlp.server.runtime.onnx;

import java.text.Normalizer;
import java.util.Locale;

/** Deterministic, dependency-free text featurizer for single-tensor sentiment models. */
final class HashedTextFeaturizer {

    private HashedTextFeaturizer() {
    }

    static float[] encode(String text, int dimensions) {
        if (dimensions < 1) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        float[] features = new float[dimensions];
        if (text == null || text.isBlank()) {
            return features;
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        int tokenCount = 0;
        int previous = -1;
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                previous = -1;
                continue;
            }
            add(features, mix(codePoint), 1.0f);
            if (previous >= 0) {
                add(features, mix(previous * 31 + codePoint), 0.5f);
            }
            previous = codePoint;
            tokenCount++;
        }
        if (tokenCount == 0) {
            return features;
        }
        float max = 0.0f;
        for (float value : features) {
            max = Math.max(max, Math.abs(value));
        }
        if (max > 0.0f) {
            for (int i = 0; i < features.length; i++) {
                features[i] /= max;
            }
        }
        return features;
    }

    private static void add(float[] features, int hash, float weight) {
        int index = Math.floorMod(hash, features.length);
        float sign = (hash & 1) == 0 ? 1.0f : -1.0f;
        features[index] += sign * weight;
    }

    private static int mix(int value) {
        int mixed = value;
        mixed ^= mixed >>> 16;
        mixed *= 0x7feb352d;
        mixed ^= mixed >>> 15;
        mixed *= 0x846ca68b;
        mixed ^= mixed >>> 16;
        return mixed;
    }
}
