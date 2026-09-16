package com.xnlp.server.pipeline.dag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Deterministic pipeline DAG planning")
class PipelineDagPlannerTest {

    private final PipelineDagPlanner planner = new PipelineDagPlanner();

    @Test
    @DisplayName("plans deterministic batches for fan-out and fan-in regardless of declaration order")
    void plan_FanOutFanInGraph_ProducesStableBatches() {
        PipelineDag first = new PipelineDag(
                List.of(node("join"), node("right"), node("root"), node("left")),
                List.of(edge("right", "join"), edge("root", "right"),
                        edge("left", "join"), edge("root", "left")));
        PipelineDag reordered = new PipelineDag(
                List.of(node("left"), node("root"), node("join"), node("right")),
                List.of(edge("root", "left"), edge("left", "join"),
                        edge("root", "right"), edge("right", "join")));

        PipelineDagPlan firstPlan = planner.plan(first);
        PipelineDagPlan reorderedPlan = planner.plan(reordered);

        assertThat(nodeIds(firstPlan.batches()))
                .containsExactly(List.of("root"), List.of("left", "right"), List.of("join"));
        assertThat(nodeIds(reorderedPlan.batches())).isEqualTo(nodeIds(firstPlan.batches()));
        assertThat(firstPlan.nodeIdsInExecutionOrder())
                .containsExactly("root", "left", "right", "join");
        assertThat(firstPlan.predecessorNodeIds())
                .containsEntry("root", List.of())
                .containsEntry("left", List.of("root"))
                .containsEntry("right", List.of("root"))
                .containsEntry("join", List.of("left", "right"));
    }

    @Test
    @DisplayName("sorts independent roots and their descendants by node identifier")
    void plan_MultipleRoots_SortsEveryReadyBatch() {
        PipelineDag dag = new PipelineDag(
                List.of(node("z-root"), node("a-root"), node("z-child"), node("a-child")),
                List.of(edge("z-root", "z-child"), edge("a-root", "a-child")));

        assertThat(nodeIds(planner.plan(dag).batches()))
                .containsExactly(List.of("a-root", "z-root"), List.of("a-child", "z-child"));
    }

    @Test
    @DisplayName("does not schedule a fan-in node until every predecessor is complete")
    void plan_UnevenFanInDepth_PlacesJoinAfterLongestDependency() {
        PipelineDag dag = new PipelineDag(
                List.of(node("root"), node("fast"), node("slow-1"), node("slow-2"), node("join")),
                List.of(edge("root", "fast"), edge("root", "slow-1"),
                        edge("slow-1", "slow-2"), edge("fast", "join"), edge("slow-2", "join")));

        assertThat(nodeIds(planner.plan(dag).batches()))
                .containsExactly(
                        List.of("root"), List.of("fast", "slow-1"), List.of("slow-2"), List.of("join"));
    }

    @Test
    @DisplayName("rejects direct and indirect cycles using the stable cycle code")
    void plan_CyclicGraph_RejectsAsCycle() {
        PipelineDag dag = new PipelineDag(
                List.of(node("a"), node("b"), node("c")),
                List.of(edge("a", "b"), edge("b", "c"), edge("c", "a")));

        assertThatThrownBy(() -> planner.plan(dag))
                .isInstanceOfSatisfying(PipelineDagException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(PipelineDagException.PIPELINE_CYCLE);
                    assertThat(exception).hasMessageContaining("cycle");
                });
    }

    @Test
    @DisplayName("plan collections are immutable snapshots")
    void plan_ValidGraph_ReturnsImmutableCollections() {
        PipelineDagPlan plan = planner.plan(new PipelineDag(List.of(node("only")), List.of()));

        assertThatThrownBy(() -> plan.batches().add(List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.batches().getFirst().add(node("other")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.predecessorNodeIds().put("other", List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static List<List<String>> nodeIds(List<List<PipelineDagNode>> batches) {
        return batches.stream()
                .map(batch -> batch.stream().map(PipelineDagNode::nodeId).toList())
                .toList();
    }

    private static PipelineDagNode node(String nodeId) {
        return new PipelineDagNode(nodeId, "test");
    }

    private static PipelineDagEdge edge(String source, String target) {
        return new PipelineDagEdge(source, target);
    }
}
