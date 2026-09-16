package com.xnlp.server.pipeline.dag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Pipeline DAG structural validation")
class PipelineDagValidatorTest {

    private final PipelineDagValidator validator = new PipelineDagValidator();

    @Test
    @DisplayName("accepts a valid fan-out and fan-in graph")
    void validate_ValidFanOutFanInGraph_AcceptsDefinition() {
        PipelineDag dag = dag(
                List.of(node("root"), node("left"), node("right"), node("join")),
                List.of(edge("root", "left"), edge("root", "right"),
                        edge("left", "join"), edge("right", "join")));

        assertThatCode(() -> validator.validate(dag)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects an empty graph with the stable invalid code")
    void validate_EmptyGraph_RejectsAsInvalid() {
        assertInvalid(dag(List.of(), List.of()), "at least one node");
    }

    @Test
    @DisplayName("rejects duplicate node identifiers")
    void validate_DuplicateNodeId_RejectsAsInvalid() {
        assertInvalid(dag(List.of(node("duplicate"), node("duplicate")), List.of()), "Duplicate");
    }

    @Test
    @DisplayName("rejects missing source and target endpoints")
    void validate_MissingEdgeEndpoint_RejectsAsInvalid() {
        assertInvalid(dag(List.of(node("present")), List.of(edge("missing", "present"))), "source");
        assertInvalid(dag(List.of(node("present")), List.of(edge("present", "missing"))), "target");
    }

    @Test
    @DisplayName("rejects duplicate edges")
    void validate_DuplicateEdge_RejectsAsInvalid() {
        assertInvalid(
                dag(List.of(node("a"), node("b")), List.of(edge("a", "b"), edge("a", "b"))),
                "Duplicate pipeline edge");
    }

    @Test
    @DisplayName("rejects self loops with the stable cycle code")
    void validate_SelfLoop_RejectsAsCycle() {
        assertThatThrownBy(() -> validator.validate(
                dag(List.of(node("loop")), List.of(edge("loop", "loop")))))
                .isInstanceOfSatisfying(PipelineDagException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(PipelineDagException.PIPELINE_CYCLE);
                    assertThat(exception).hasMessageContaining("itself");
                });
    }

    @Test
    @DisplayName("record constructors reject malformed definitions")
    void construct_MalformedRecords_RejectsAsInvalid() {
        assertThatThrownBy(() -> new PipelineDagNode(" ", "capability"))
                .isInstanceOfSatisfying(PipelineDagException.class,
                        exception -> assertThat(exception.code()).isEqualTo("pipeline_invalid"));
        assertThatThrownBy(() -> new PipelineDagNode("node", " "))
                .isInstanceOf(PipelineDagException.class);
        assertThatThrownBy(() -> new PipelineDagEdge(null, "target"))
                .isInstanceOf(PipelineDagException.class);
        assertThatThrownBy(() -> new PipelineDag(null, List.of()))
                .isInstanceOf(PipelineDagException.class);
    }

    private void assertInvalid(PipelineDag dag, String messagePart) {
        assertThatThrownBy(() -> validator.validate(dag))
                .isInstanceOfSatisfying(PipelineDagException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(PipelineDagException.PIPELINE_INVALID);
                    assertThat(exception).hasMessageContaining(messagePart);
                });
    }

    private static PipelineDag dag(List<PipelineDagNode> nodes, List<PipelineDagEdge> edges) {
        return new PipelineDag(nodes, edges);
    }

    private static PipelineDagNode node(String nodeId) {
        return new PipelineDagNode(nodeId, "test");
    }

    private static PipelineDagEdge edge(String source, String target) {
        return new PipelineDagEdge(source, target);
    }
}
