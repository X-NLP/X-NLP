package com.xnlp.server.dataset.versioning;

import com.xnlp.core.eval.EvaluationDataset;
import com.xnlp.core.eval.EvaluationEntry;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Bridges datasets from the existing evaluation API into the versioned dataset store. */
@Service
public class DatasetVersionBootstrap {

    private final DatasetVersionRepository repository;

    public DatasetVersionBootstrap(DatasetVersionRepository repository) {
        this.repository = repository;
    }

    public VersionedDataset bootstrap(
            String tenantId, EvaluationDataset source, String createdBy, Instant now) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(now, "now");
        String datasetId = DatasetVersioningSupport.text(source.getId(), "dataset.id", 64);
        DatasetMetadata metadata = new DatasetMetadata(
                source.getName(), source.getDescription(),
                source.getTaskType() == null ? null : source.getTaskType().name());
        List<DatasetEntryData> entries = entries(source.getEntries());
        Instant createdAt = source.getCreatedAt() == null ? now : source.getCreatedAt();
        Instant updatedAt = source.getUpdatedAt() == null ? now : source.getUpdatedAt();
        return repository.bootstrapDataset(tenantId, datasetId, metadata, entries, createdBy, createdAt, updatedAt);
    }

    private static List<DatasetEntryData> entries(List<EvaluationEntry> sourceEntries) {
        if (sourceEntries == null || sourceEntries.isEmpty()) return List.of();
        List<DatasetEntryData> converted = new ArrayList<>(sourceEntries.size());
        Set<String> ids = new HashSet<>();
        for (int sequence = 0; sequence < sourceEntries.size(); sequence++) {
            EvaluationEntry entry = Objects.requireNonNull(sourceEntries.get(sequence),
                    "dataset entries must not contain null");
            DatasetEntryData value = new DatasetEntryData(entry.getId(), sequence, entry.getInput(),
                    entry.getExpectedOutput(), entry.getLabels(), entry.getMetadata());
            if (!ids.add(value.id())) {
                throw new IllegalArgumentException("Duplicate dataset entry id: " + value.id());
            }
            converted.add(value);
        }
        return List.copyOf(converted);
    }
}
