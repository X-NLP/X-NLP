package com.xnlp.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xnlp.core.eval.EvaluationDataset;
import com.xnlp.core.eval.EvaluationEntry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages evaluation datasets with JSON file-based persistence.
 *
 * <p>Datasets are stored as JSON files under {@code data/datasets/} and
 * also cached in memory. Each dataset file is named {@code <id>.json}.
 */
@Service
public class DatasetService {

    private static final Logger log = LoggerFactory.getLogger(DatasetService.class);
    private static final Path STORE_DIR = Paths.get("data", "datasets");

    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Map<String, EvaluationDataset> cache = new ConcurrentHashMap<>();

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(STORE_DIR);
        // Load existing datasets from disk on startup
        try (var stream = Files.list(STORE_DIR)) {
            stream.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                try {
                    EvaluationDataset ds = mapper.readValue(p.toFile(), EvaluationDataset.class);
                    cache.put(ds.getId(), ds);
                    log.info("Loaded dataset: {} ({} entries)", ds.getName(), ds.getEntryCount());
                } catch (IOException e) {
                    log.warn("Failed to load dataset from {}", p, e);
                }
            });
        }
    }

    public List<EvaluationDataset> list() {
        return cache.values().stream()
                .sorted(Comparator.comparing(EvaluationDataset::getUpdatedAt).reversed())
                .toList();
    }

    public Optional<EvaluationDataset> get(String id) {
        return Optional.ofNullable(cache.get(id));
    }

    public EvaluationDataset create(EvaluationDataset dataset) throws IOException {
        dataset.setId(UUID.randomUUID().toString());
        dataset.setCreatedAt(Instant.now());
        dataset.setUpdatedAt(Instant.now());
        dataset.setEntryCount(dataset.getEntries() != null ? dataset.getEntries().size() : 0);
        persist(dataset);
        cache.put(dataset.getId(), dataset);
        log.info("Created dataset: {} ({} entries)", dataset.getName(), dataset.getEntryCount());
        return dataset;
    }

    public EvaluationDataset update(String id, EvaluationDataset updated) throws IOException {
        EvaluationDataset existing = cache.get(id);
        if (existing == null) {
            throw new NoSuchElementException("Dataset not found: " + id);
        }
        existing.setName(updated.getName());
        existing.setDescription(updated.getDescription());
        existing.setTaskType(updated.getTaskType());
        if (updated.getEntries() != null) {
            existing.setEntries(updated.getEntries());
            existing.setEntryCount(updated.getEntries().size());
        }
        existing.setUpdatedAt(Instant.now());
        persist(existing);
        cache.put(id, existing);
        return existing;
    }

    public void delete(String id) throws IOException {
        cache.remove(id);
        Path file = STORE_DIR.resolve(id + ".json");
        Files.deleteIfExists(file);
        log.info("Deleted dataset: {}", id);
    }

    public List<EvaluationEntry> getEntries(String id, int page, int size) {
        EvaluationDataset ds = cache.get(id);
        if (ds == null) throw new NoSuchElementException("Dataset not found: " + id);
        List<EvaluationEntry> entries = ds.getEntries();
        int from = page * size;
        int to = Math.min(from + size, entries.size());
        if (from >= entries.size()) return List.of();
        return entries.subList(from, to);
    }

    public String exportJson(String id) {
        EvaluationDataset ds = cache.get(id);
        if (ds == null) throw new NoSuchElementException("Dataset not found: " + id);
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(ds);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize dataset", e);
        }
    }

    private void persist(EvaluationDataset dataset) throws IOException {
        Path file = STORE_DIR.resolve(dataset.getId() + ".json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), dataset);
    }

    public int count() { return cache.size(); }
}
