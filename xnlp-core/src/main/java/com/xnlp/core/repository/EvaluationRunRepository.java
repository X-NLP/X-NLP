package com.xnlp.core.repository;

import com.xnlp.core.eval.EvaluationRun;

import java.util.List;
import java.util.Optional;

/**
 * Repository abstraction for evaluation run persistence.
 *
 * @see ModelConfigRepository for pattern rationale
 */
public interface EvaluationRunRepository {

    List<EvaluationRun> findAll();

    Optional<EvaluationRun> findById(String id);

    EvaluationRun save(EvaluationRun run);
}
