package com.xnlp.server.evaluation.recovery;

import com.xnlp.core.eval.EvaluationDataset;
import com.xnlp.server.dataset.versioning.DatasetSnapshot;
import com.xnlp.server.dataset.versioning.DatasetVersionBootstrap;
import com.xnlp.server.dataset.versioning.DatasetVersionRepository;
import com.xnlp.server.dataset.versioning.DatasetWriteStatus;
import com.xnlp.server.service.DatasetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.NoSuchElementException;

@Service
public class EvaluationDatasetSnapshotResolver {

    private final DatasetService datasets;
    private final DatasetVersionBootstrap bootstrap;
    private final DatasetVersionRepository versions;

    public EvaluationDatasetSnapshotResolver(
            DatasetService datasets,
            DatasetVersionBootstrap bootstrap,
            DatasetVersionRepository versions) {
        this.datasets = datasets;
        this.bootstrap = bootstrap;
        this.versions = versions;
    }

    @Transactional
    public PinnedDataset pin(String tenantId, String datasetId, String actorId, Instant now) {
        EvaluationDataset legacy = datasets.get(datasetId)
                .orElseThrow(() -> new NoSuchElementException("Dataset not found: " + datasetId));
        var current = versions.findDataset(tenantId, datasetId)
                .orElseGet(() -> bootstrap.bootstrap(tenantId, legacy, actorId, now));
        DatasetSnapshot snapshot = versions.findSnapshot(tenantId, datasetId, current.version())
                .orElseGet(() -> {
                    var result = versions.createSnapshot(tenantId, datasetId, current.version(), actorId, now);
                    if (result.status() != DatasetWriteStatus.APPLIED) {
                        throw new IllegalStateException("Unable to pin dataset version " + current.version());
                    }
                    return result.value();
                });
        return new PinnedDataset(legacy, snapshot);
    }

    public DatasetSnapshot resolve(String tenantId, String datasetId, long version) {
        return versions.findSnapshot(tenantId, datasetId, version)
                .orElseThrow(() -> new IllegalStateException(
                        "Pinned dataset version is unavailable: " + datasetId + "@" + version));
    }

    public record PinnedDataset(EvaluationDataset dataset, DatasetSnapshot snapshot) {
    }
}
