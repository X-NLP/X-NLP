package com.xnlp.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xnlp.core.eval.EvaluationDataset;
import com.xnlp.core.eval.EvaluationEntry;
import com.xnlp.core.repository.DatasetRepository;
import com.xnlp.server.dataset.versioning.DatasetEntryData;
import com.xnlp.server.dataset.versioning.DatasetMetadata;
import com.xnlp.server.dataset.versioning.DatasetVersionBootstrap;
import com.xnlp.server.dataset.versioning.DatasetVersionRepository;
import com.xnlp.server.dataset.versioning.DatasetWriteStatus;
import com.xnlp.server.dataset.versioning.VersionedDatasetEntry;
import com.xnlp.server.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final DatasetVersionBootstrap versionBootstrap;
    private final DatasetVersionRepository versions;

    public DatasetService(
            DatasetRepository repository,
            DatasetVersionBootstrap versionBootstrap,
            DatasetVersionRepository versions) {
        this.repository = repository;
        this.versionBootstrap = versionBootstrap;
        this.versions = versions;
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
        versionBootstrap.bootstrap(
                TenantContext.currentTenantId(), dataset, currentActor(), dataset.getUpdatedAt());
        log.info("Created dataset: {} ({} entries)", dataset.getName(), dataset.getEntryCount());
        return dataset;
    }

    @Transactional
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
        synchronizeVersioned(existing);
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

    private void synchronizeVersioned(EvaluationDataset source) {
        String tenantId = TenantContext.currentTenantId();
        Instant now = source.getUpdatedAt() == null ? Instant.now() : source.getUpdatedAt();
        var current = versions.findDataset(tenantId, source.getId())
                .orElseGet(() -> versionBootstrap.bootstrap(tenantId, source, currentActor(), now));
        long version = current.version();
        DatasetMetadata metadata = new DatasetMetadata(
                source.getName(), source.getDescription(),
                source.getTaskType() == null ? null : source.getTaskType().name());
        var metadataResult = versions.updateDataset(tenantId, source.getId(), version, metadata, now);
        if (metadataResult.status() != DatasetWriteStatus.APPLIED) throw versionConflict();
        version = metadataResult.currentVersion();

        Set<String> desiredIds = new HashSet<>();
        List<EvaluationEntry> entries = source.getEntries() == null ? List.of() : source.getEntries();
        for (int sequence = 0; sequence < entries.size(); sequence++) {
            EvaluationEntry entry = entries.get(sequence);
            desiredIds.add(entry.getId());
            DatasetEntryData data = new DatasetEntryData(
                    entry.getId(), sequence, entry.getInput(), entry.getExpectedOutput(),
                    entry.getLabels(), entry.getMetadata());
            var result = versions.putEntry(tenantId, source.getId(), version, data, now);
            if (result.status() != DatasetWriteStatus.APPLIED) throw versionConflict();
            version = result.currentVersion();
        }
        for (VersionedDatasetEntry entry : versions.findEntries(tenantId, source.getId())) {
            if (desiredIds.contains(entry.data().id())) continue;
            var result = versions.deleteEntry(tenantId, source.getId(), entry.data().id(), version, now);
            if (result.status() != DatasetWriteStatus.APPLIED) throw versionConflict();
            version = result.currentVersion();
        }
        var snapshot = versions.createSnapshot(tenantId, source.getId(), version, currentActor(), now);
        if (snapshot.status() != DatasetWriteStatus.APPLIED) throw versionConflict();
    }

    private static IllegalStateException versionConflict() {
        return new IllegalStateException("Dataset version changed while applying the legacy update");
    }

    private static String currentActor() {
        return "system";
    }
}
