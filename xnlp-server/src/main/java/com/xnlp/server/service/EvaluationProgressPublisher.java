package com.xnlp.server.service;

import com.xnlp.core.eval.EvaluationRun;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Publishes persisted evaluation snapshots to browser and operational clients.
 * The repository remains the source of truth; this component is only a
 * best-effort low-latency notification channel and reconnects are safe.
 */
@Component
public class EvaluationProgressPublisher {

    private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L;

    private final ConcurrentHashMap<String, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String runId, Supplier<Optional<EvaluationRun>> currentRun) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        subscribers.computeIfAbsent(runId, ignored -> ConcurrentHashMap.newKeySet()).add(emitter);
        Runnable remove = () -> remove(runId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(ignored -> remove.run());

        currentRun.get().ifPresent(run -> send(runId, emitter, run, false));
        return emitter;
    }

    public void publish(EvaluationRun run) {
        Set<SseEmitter> emitters = subscribers.get(run.getId());
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        boolean terminal = isTerminal(run.getStatus());
        for (SseEmitter emitter : Set.copyOf(emitters)) {
            send(run.getId(), emitter, run, terminal);
        }
    }

    private void send(String runId, SseEmitter emitter, EvaluationRun run, boolean terminal) {
        try {
            emitter.send(SseEmitter.event()
                    .name("evaluation-progress")
                    .id(run.getId())
                    .data(run));
            if (terminal) {
                emitter.complete();
                remove(runId, emitter);
            }
        } catch (IOException | IllegalStateException ex) {
            remove(runId, emitter);
            try {
                emitter.completeWithError(ex);
            } catch (IllegalStateException ignored) {
                // The emitter may already have been completed by the client.
            }
        }
    }

    private void remove(String runId, SseEmitter emitter) {
        Set<SseEmitter> emitters = subscribers.get(runId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            subscribers.remove(runId, emitters);
        }
    }

    private boolean isTerminal(String status) {
        return "completed".equals(status) || "failed".equals(status) || "cancelled".equals(status);
    }
}
