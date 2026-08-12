package com.xnlp.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xnlp.core.eval.EvaluationDataset;
import com.xnlp.core.eval.EvaluationEntry;
import com.xnlp.core.repository.DatasetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

/**
 * Manages evaluation datasets through the configured persistence profile.
 *
 * <p>Delegates storage to a {@link DatasetRepository} implementation.
 */
@Service
public class DatasetService {

    private static final Logger log = LoggerFactory.getLogger(DatasetService.class);

    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules();
    private final DatasetRepository repository;

    public DatasetService(DatasetRepository repository) {
        this.repository = repository;
    }

    public List<EvaluationDataset> list() {
        return repository.findAll();
    }

    public Optional<EvaluationDataset> get(String id) {
        return repository.findById(id);
    }

    public EvaluationDataset create(EvaluationDataset dataset) {
        dataset.setId(UUID.randomUUID().toString());
        dataset.setCreatedAt(Instant.now());
        dataset.setUpdatedAt(Instant.now());
        dataset.setEntryCount(dataset.getEntries() != null ? dataset.getEntries().size() : 0);
        repository.save(dataset);
        log.info("Created dataset: {} ({} entries)", dataset.getName(), dataset.getEntryCount());
        return dataset;
    }

    public EvaluationDataset update(String id, EvaluationDataset updated) {
        EvaluationDataset existing = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Dataset not found: " + id));
        existing.setName(updated.getName());
        existing.setDescription(updated.getDescription());
        existing.setTaskType(updated.getTaskType());
        if (updated.getEntries() != null) {
            existing.setEntries(updated.getEntries());
            existing.setEntryCount(updated.getEntries().size());
        }
        existing.setUpdatedAt(Instant.now());
        repository.save(existing);
        return existing;
    }

    public void delete(String id) {
        repository.deleteById(id);
    }

    public List<EvaluationEntry> getEntries(String id, int page, int size) {
        EvaluationDataset ds = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Dataset not found: " + id));
        List<EvaluationEntry> entries = ds.getEntries();
        int from = page * size;
        int to = Math.min(from + size, entries.size());
        if (from >= entries.size()) return List.of();
        return entries.subList(from, to);
    }

    public String exportJson(String id) {
        EvaluationDataset ds = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Dataset not found: " + id));
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(ds);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize dataset", e);
        }
    }

    public int count() { return repository.count(); }
}
