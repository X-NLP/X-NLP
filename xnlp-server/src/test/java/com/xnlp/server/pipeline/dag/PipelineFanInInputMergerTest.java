package com.xnlp.server.pipeline.dag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Deterministic pipeline fan-in input merging")
class PipelineFanInInputMergerTest {

    private final PipelineFanInInputMerger merger = new PipelineFanInInputMerger();

    @Test
    @DisplayName("later predecessor node identifiers win collisions independent of map insertion order")
    void merge_CollidingPredecessorValues_UsesStableNodeIdPrecedence() {
        Map<String, Map<String, Object>> outputs = new LinkedHashMap<>();
        outputs.put("z-node", Map.of("shared", "z", "z-only", 3));
        outputs.put("a-node", Map.of("shared", "a", "a-only", 2));

        Map<String, Object> merged = merger.merge(Map.of("shared", "run", "run-only", 1), outputs);

        assertThat(merged)
                .containsEntry("run-only", 1)
                .containsEntry("a-only", 2)
                .containsEntry("z-only", 3)
                .containsEntry("shared", "z");
    }

    @Test
    @DisplayName("equivalent unordered inputs always produce an equivalent result")
    void merge_UnorderedMaps_ProducesEquivalentResult() {
        Map<String, Object> runInput = new HashMap<>();
        runInput.put("beta", 2);
        runInput.put("alpha", 1);
        Map<String, Map<String, Object>> first = new HashMap<>();
        first.put("right", Map.of("value", "right"));
        first.put("left", Map.of("value", "left"));
        Map<String, Map<String, Object>> second = new LinkedHashMap<>();
        second.put("left", Map.of("value", "left"));
        second.put("right", Map.of("value", "right"));

        assertThat(merger.merge(runInput, first)).isEqualTo(merger.merge(runInput, second));
        assertThat(merger.merge(runInput, first)).containsEntry("value", "right");
    }

    @Test
    @DisplayName("returns an immutable snapshot that is not affected by source mutation")
    void merge_MutableInput_ReturnsImmutableSnapshot() {
        Map<String, Object> runInput = new HashMap<>();
        runInput.put("input", "before");

        Map<String, Object> merged = merger.merge(runInput, Map.of());
        runInput.put("input", "after");

        assertThat(merged).containsEntry("input", "before");
        assertThatThrownBy(() -> merged.put("other", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("rejects malformed merge inputs with the stable invalid code")
    void merge_MalformedInput_RejectsAsInvalid() {
        assertInvalid(() -> merger.merge(null, Map.of()));
        assertInvalid(() -> merger.merge(Map.of(), null));
        Map<String, Map<String, Object>> nullOutput = new HashMap<>();
        nullOutput.put("source", null);
        assertInvalid(() -> merger.merge(Map.of(), nullOutput));
    }

    private void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(PipelineDagException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(PipelineDagException.PIPELINE_INVALID));
    }
}
