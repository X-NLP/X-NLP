package com.xnlp.core.repository;

import com.xnlp.core.eval.EvaluationDataset;

import java.util.List;
import java.util.Optional;

/**
 * Repository abstraction for evaluation dataset persistence.
 *
 * @see ModelConfigRepository for pattern rationale
 */
public interface DatasetRepository {

    List<EvaluationDataset> findAll();

    Optional<EvaluationDataset> findById(String id);

    EvaluationDataset save(EvaluationDataset dataset);

    void deleteById(String id);

    int count();
}
