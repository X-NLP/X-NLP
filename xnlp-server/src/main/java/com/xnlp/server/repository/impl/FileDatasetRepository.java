package com.xnlp.server.repository.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.xnlp.core.eval.EvaluationDataset;
import com.xnlp.core.repository.DatasetRepository;
import com.xnlp.server.tenant.TenantContext;
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
        loadDirectory(STORE_DIR, TenantContext.DEFAULT_TENANT_ID);
        try (var stream = Files.list(STORE_DIR)) {
            stream.filter(Files::isDirectory).forEach(path -> {
                String tenantId = TenantContext.normalize(path.getFileName().toString());
                try {
                    loadDirectory(path, tenantId);
                } catch (IOException e) {
                    log.warn("Failed to load datasets for tenant {}", tenantId, e);
                }
            });
        }
    }

    @Override
    public List<EvaluationDataset> findAll() {
        String prefix = keyPrefix(TenantContext.currentTenantId());
        return cache.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .sorted(Comparator.comparing(EvaluationDataset::getUpdatedAt).reversed())
                .toList();
    }

    @Override
    public Optional<EvaluationDataset> findById(String id) {
        return Optional.ofNullable(cache.get(key(id)));
    }

    @Override
    public EvaluationDataset save(EvaluationDataset dataset) {
        try {
            Path directory = tenantDirectory(TenantContext.currentTenantId());
            Files.createDirectories(directory);
            Path file = directory.resolve(dataset.getId() + ".json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), dataset);
            cache.put(key(dataset.getId()), dataset);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to persist dataset: " + dataset.getId(), e);
        }
        return dataset;
    }

    @Override
    public void deleteById(String id) {
        try {
            cache.remove(key(id));
            Files.deleteIfExists(tenantDirectory(TenantContext.currentTenantId()).resolve(id + ".json"));
            log.info("Deleted dataset: {}", id);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete dataset: " + id, e);
        }
    }

    @Override
    public int count() {
        String prefix = keyPrefix(TenantContext.currentTenantId());
        return (int) cache.keySet().stream().filter(key -> key.startsWith(prefix)).count();
    }

    private void loadDirectory(Path directory, String tenantId) throws IOException {
        if (!Files.exists(directory)) return;
        try (var stream = Files.list(directory)) {
            stream.filter(path -> path.toString().endsWith(".json")).forEach(path -> {
                try {
                    EvaluationDataset ds = mapper.readValue(path.toFile(), EvaluationDataset.class);
                    cache.put(key(tenantId, ds.getId()), ds);
                    log.info("Loaded dataset for tenant {}: {} ({} entries)",
                            tenantId, ds.getName(), ds.getEntryCount());
                } catch (IOException e) {
                    log.warn("Failed to load dataset from {}", path, e);
                }
            });
        }
    }

    private Path tenantDirectory(String tenantId) {
        return TenantContext.DEFAULT_TENANT_ID.equals(tenantId)
                ? STORE_DIR : STORE_DIR.resolve(tenantId);
    }

    private String key(String id) {
        return key(TenantContext.currentTenantId(), id);
    }

    private static String key(String tenantId, String id) {
        return tenantId + "\0" + id;
    }

    private static String keyPrefix(String tenantId) {
        return tenantId + "\0";
    }
}
