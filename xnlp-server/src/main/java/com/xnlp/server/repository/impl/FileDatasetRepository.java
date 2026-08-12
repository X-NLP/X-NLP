package com.xnlp.server.repository.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.xnlp.core.eval.EvaluationDataset;
import com.xnlp.core.repository.DatasetRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-backed implementation of {@link DatasetRepository}.
 *
 * <p>Each dataset is stored as {@code data/datasets/<id>.json}.
 */
@Component
@Profile("file")
public class FileDatasetRepository implements DatasetRepository {

    private static final Logger log = LoggerFactory.getLogger(FileDatasetRepository.class);
    private static final Path STORE_DIR = Paths.get("data", "datasets");

    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Map<String, EvaluationDataset> cache = new ConcurrentHashMap<>();

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(STORE_DIR);
        try (var stream = Files.list(STORE_DIR)) {
            stream.filter(path -> path.toString().endsWith(".json")).forEach(path -> {
                try {
                    EvaluationDataset ds = mapper.readValue(path.toFile(), EvaluationDataset.class);
                    cache.put(ds.getId(), ds);
                    log.info("Loaded dataset: {} ({} entries)", ds.getName(), ds.getEntryCount());
                } catch (IOException e) {
                    log.warn("Failed to load dataset from {}", path, e);
                }
            });
        }
    }

    @Override
    public List<EvaluationDataset> findAll() {
        return cache.values().stream()
                .sorted(Comparator.comparing(EvaluationDataset::getUpdatedAt).reversed())
                .toList();
    }

    @Override
    public Optional<EvaluationDataset> findById(String id) {
        return Optional.ofNullable(cache.get(id));
    }

    @Override
    public EvaluationDataset save(EvaluationDataset dataset) {
        try {
            Path file = STORE_DIR.resolve(dataset.getId() + ".json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), dataset);
            cache.put(dataset.getId(), dataset);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to persist dataset: " + dataset.getId(), e);
        }
        return dataset;
    }

    @Override
    public void deleteById(String id) {
        try {
            cache.remove(id);
            Files.deleteIfExists(STORE_DIR.resolve(id + ".json"));
            log.info("Deleted dataset: {}", id);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete dataset: " + id, e);
        }
    }

    @Override
    public int count() {
        return cache.size();
    }
}
