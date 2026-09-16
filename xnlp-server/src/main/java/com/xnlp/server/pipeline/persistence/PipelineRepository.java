package com.xnlp.server.pipeline.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PipelineRepository {

    PipelineDefinition createDefinition(PipelineDefinition definition);

    PipelineWriteResult updateDefinition(PipelineDefinition definition, long expectedVersion);

    Optional<PipelineDefinition> findDefinition(String tenantId, String pipelineId);

    Optional<PipelineDefinition> findDefinitionVersion(String tenantId, String pipelineId, long version);

    PipelineRun createRun(PipelineRun run);

    Optional<PipelineRun> findRun(String tenantId, String runId);

    List<PipelineRun> findRecoverableRuns();

    boolean compareAndSetRunStatus(
            String tenantId, String runId, PipelineRunStatus expected, PipelineRunStatus next,
            long expectedRevision, Instant now, String outputJson, String errorCode, String errorMessage);

    boolean requestCancellation(String tenantId, String runId, long expectedRevision, Instant now);

    PipelineNodeAttempt createNodeAttempt(PipelineNodeAttempt attempt);

    boolean completeNodeAttempt(
            String tenantId, String runId, String nodeId, int attempt,
            NodeAttemptStatus expected, NodeAttemptStatus terminal,
            Instant completedAt, String outputJson, String errorCode, String errorMessage);

    List<PipelineNodeAttempt> findNodeAttempts(String tenantId, String runId);

    PipelineRunEvent appendEvent(PipelineRunEvent event);

    List<PipelineRunEvent> findEvents(String tenantId, String runId, long afterSequence, int limit);
}
