package com.xnlp.server.nlp;

import com.xnlp.core.api.NlpComponent;
import com.xnlp.core.api.NlpContext;
import com.xnlp.core.api.ComponentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry holding all registered {@link NlpComponent} implementations.
 *
 * <p>This is the replacement for the monolithic {@code listTasks()} from
 * the old {@code NLPTaskService}.  Components register themselves on
 * construction, and the registry exposes their metadata and execution
 * capability.
 *
 * <p>Patterned after HanLP's component registry and Spring's
 * {@code ApplicationContext} bean discovery.
 */
@Component
public class CapabilityRegistry {

    private static final Logger log = LoggerFactory.getLogger(CapabilityRegistry.class);

    private final Map<String, NlpComponent> components = new ConcurrentHashMap<>();

    /** Called by each {@code NlpComponent} bean during post-construction. */
    public void register(NlpComponent component) {
        components.put(component.id(), component);
        log.info("Registered NLP component: {} ({})", component.id(), component.displayName());
    }

    public List<NlpComponent> listAll() {
        return components.values().stream()
                .sorted(Comparator.comparing(NlpComponent::id))
                .toList();
    }

    public Optional<NlpComponent> get(String id) {
        return Optional.ofNullable(components.get(id.toUpperCase()));
    }

    /**
     * Execute the named component with the given context.
     *
     * @throws NoSuchElementException if no component matches the id
     */
    public ComponentResult execute(String componentId, NlpContext context) {
        NlpComponent component = get(componentId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Unsupported NLP capability: " + componentId));
        return component.execute(context);
    }

    /**
     * Convert component metadata to the legacy task-list format
     * for backward API compatibility.
     */
    public List<Map<String, Object>> listTasks() {
        return listAll().stream().map(comp -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("task", comp.id());
            entry.put("description", comp.description());
            entry.put("parameters", comp.parameterSchema());
            return entry;
        }).toList();
    }
}
