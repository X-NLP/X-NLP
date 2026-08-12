package com.xnlp.server.repository.impl;

import com.xnlp.core.eval.EvaluationRun;
import com.xnlp.core.repository.EvaluationRunRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link EvaluationRunRepository}.
 *
 * <p>Evaluation runs are transient; a future iteration could persist them
 * to files or a database without changing consumer code.
 */
@Component
@Profile("memory")
public class InMemoryEvaluationRunRepository implements EvaluationRunRepository {

    private final Map<String, EvaluationRun> store = new ConcurrentHashMap<>();

    @Override
    public List<EvaluationRun> findAll() {
        return store.values().stream()
                .sorted(Comparator.comparing(EvaluationRun::getCreatedAt).reversed())
                .toList();
    }

    @Override
    public Optional<EvaluationRun> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public EvaluationRun save(EvaluationRun run) {
        store.put(run.getId(), run);
        return run;
    }
}
