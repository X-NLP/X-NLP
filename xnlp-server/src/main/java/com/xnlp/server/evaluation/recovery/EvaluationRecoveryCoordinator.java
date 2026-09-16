package com.xnlp.server.evaluation.recovery;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/** Requeues durable non-terminal runs after application startup. */
@Component
public class EvaluationRecoveryCoordinator implements ApplicationListener<ApplicationReadyEvent> {

    private final EvaluationRecoveryRepository repository;
    private volatile Consumer<RecoveryRun> dispatcher = ignored -> { };

    public EvaluationRecoveryCoordinator(EvaluationRecoveryRepository repository) {
        this.repository = repository;
    }

    public void register(Consumer<RecoveryRun> dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        repository.findRecoverableRuns().forEach(dispatcher);
    }
}
