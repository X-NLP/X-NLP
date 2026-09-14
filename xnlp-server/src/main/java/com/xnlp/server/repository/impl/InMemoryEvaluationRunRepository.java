package com.xnlp.server.repository.impl;

import com.xnlp.core.eval.EvaluationRun;
import com.xnlp.core.repository.EvaluationRunRepository;
import com.xnlp.server.tenant.TenantContext;
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
        String prefix = TenantContext.currentTenantId() + "\0";
        return store.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .sorted(Comparator.comparing(EvaluationRun::getCreatedAt).reversed())
                .toList();
    }

    @Override
    public Optional<EvaluationRun> findById(String id) {
        return Optional.ofNullable(store.get(key(id)));
    }

    @Override
    public EvaluationRun save(EvaluationRun run) {
        store.put(key(run.getId()), run);
        return run;
    }

    private String key(String id) {
        return TenantContext.currentTenantId() + "\0" + id;
    }
}
