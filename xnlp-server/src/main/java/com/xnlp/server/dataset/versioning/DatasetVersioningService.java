package com.xnlp.server.dataset.versioning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xnlp.server.dto.DatasetEntryMutationRequest;
import com.xnlp.server.dto.DatasetEntryMutationResponse;
import com.xnlp.server.dto.DatasetImportEntryRequest;
import com.xnlp.server.dto.DatasetImportErrorResponse;
import com.xnlp.server.dto.DatasetImportJobResponse;
import com.xnlp.server.dto.DatasetImportRequest;
import com.xnlp.server.dto.DatasetImportResponse;
import com.xnlp.server.dto.PageResponse;
import com.xnlp.server.dto.VersionedDatasetVersionResponse;
import com.xnlp.server.security.XnlpPrincipal;
import com.xnlp.server.tenant.TenantContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DatasetVersioningService {

    private static final int MAX_ERROR_PAGE_SIZE = 1_000;

    private final DatasetVersionRepository datasets;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public DatasetVersioningService(DatasetVersionRepository datasets, ObjectMapper objectMapper) {
        this(datasets, objectMapper, Clock.systemUTC());
    }

    DatasetVersioningService(DatasetVersionRepository datasets, ObjectMapper objectMapper, Clock clock) {
        this.datasets = datasets;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public DatasetEntryMutationResponse createEntry(String datasetId, DatasetEntryMutationRequest request) {
        if (request.id() == null || request.id().isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        return putEntry(datasetId, request.id(), request);
    }

    @Transactional
    public DatasetEntryMutationResponse replaceEntry(
            String datasetId, String entryId, DatasetEntryMutationRequest request) {
        if (request.id() != null && !request.id().isBlank() && !request.id().trim().equals(entryId)) {
            throw new IllegalArgumentException("body id must match entryId");
        }
        String tenantId = tenantId();
        boolean exists = datasets.findEntries(tenantId, datasetId).stream()
                .anyMatch(entry -> entry.data().id().equals(entryId));
        if (!exists) {
            if (datasets.findDataset(tenantId, datasetId).isEmpty()) {
                throw DatasetVersioningException.datasetNotFound();
            }
            throw DatasetVersioningException.entryNotFound();
        }
        return putEntry(datasetId, entryId, request);
    }

    @Transactional
    public void deleteEntry(String datasetId, String entryId, long expectedVersion) {
        String tenantId = tenantId();
        boolean exists = datasets.findEntries(tenantId, datasetId).stream()
                .anyMatch(entry -> entry.data().id().equals(entryId));
        if (!exists) {
            if (datasets.findDataset(tenantId, datasetId).isEmpty()) {
                throw DatasetVersioningException.datasetNotFound();
            }
            throw DatasetVersioningException.entryNotFound();
        }
        Instant now = clock.instant();
        DatasetWriteResult<VersionedDataset> result = datasets.deleteEntry(
                tenantId, datasetId, entryId, expectedVersion, now);
        resolve(result);
        createSnapshot(tenantId, datasetId, result.currentVersion(), now);
    }

    public PageResponse<VersionedDatasetVersionResponse> versions(
            String datasetId, int page, int size) {
        String tenantId = tenantId();
        requireDataset(tenantId, datasetId);
        List<VersionedDatasetVersionResponse> versions = datasets.findSnapshots(tenantId, datasetId).stream()
                .map(VersionedDatasetVersionResponse::from)
                .toList();
        int offset = Math.multiplyExact(page, size);
        List<VersionedDatasetVersionResponse> items = offset >= versions.size()
                ? List.of() : versions.subList(offset, Math.min(versions.size(), offset + size));
        return PageResponse.of(items, page, size, versions.size());
    }

    @Transactional
    public DatasetImportResponse importEntries(String datasetId, DatasetImportRequest request) {
        String tenantId = tenantId();
        VersionedDataset current = requireDataset(tenantId, datasetId);
        if (current.version() != request.expectedVersion()) {
            throw DatasetVersioningException.versionConflict(current.version());
        }

        Instant startedAt = clock.instant();
        String jobId = UUID.randomUUID().toString();
        String actor = actor();
        datasets.createImportJob(
                tenantId, jobId, datasetId, request.sourceName(), request.entries().size(),
                request.expectedVersion(), actor, startedAt);
        datasets.updateImportJob(
                tenantId, jobId, DatasetImportStatus.RUNNING, 0, 0, null, null,
                startedAt, null);

        long version = request.expectedVersion();
        int imported = 0;
        int failed = 0;
        List<DatasetImportError> errors = new ArrayList<>();
        List<DatasetImportEntryRequest> rows = request.entries();
        for (int index = 0; index < rows.size(); index++) {
            DatasetImportEntryRequest row = rows.get(index);
            int rowNumber = index + 1;
            try {
                DatasetEntryData data = toEntry(row);
                DatasetWriteResult<VersionedDatasetEntry> result = datasets.putEntry(
                        tenantId, datasetId, version, data, clock.instant());
                if (!result.applied()) {
                    if (result.status() == DatasetWriteStatus.NOT_FOUND) {
                        throw DatasetVersioningException.datasetNotFound();
                    }
                    throw DatasetVersioningException.versionConflict(result.currentVersion());
                }
                version = result.currentVersion();
                imported++;
            } catch (IllegalArgumentException exception) {
                failed++;
                errors.add(appendError(
                        tenantId, jobId, rowNumber, "dataset_import_invalid", exception.getMessage(), row));
            }
        }

        Instant completedAt = clock.instant();
        DatasetImportStatus status = failed == 0
                ? DatasetImportStatus.COMPLETED : DatasetImportStatus.COMPLETED_WITH_ERRORS;
        String summary = failed == 0 ? null : failed + " import row(s) failed validation";
        datasets.updateImportJob(
                tenantId, jobId, status, imported, failed, version, summary, completedAt, completedAt);
        if (imported > 0) {
            createSnapshot(tenantId, datasetId, version, completedAt);
        }
        DatasetImportJob job = datasets.findImportJob(tenantId, jobId).orElseThrow();
        return response(job, errors);
    }

    public DatasetImportResponse importReport(String datasetId, String jobId, int limit, int offset) {
        String tenantId = tenantId();
        requireDataset(tenantId, datasetId);
        DatasetImportJob job = datasets.findImportJob(tenantId, jobId)
                .filter(value -> value.datasetId().equals(datasetId))
                .orElseThrow(DatasetVersioningException::importNotFound);
        List<DatasetImportError> errors = datasets.findImportErrors(
                tenantId, jobId, Math.min(limit, MAX_ERROR_PAGE_SIZE), offset);
        return response(job, errors);
    }

    private DatasetEntryMutationResponse putEntry(
            String datasetId, String entryId, DatasetEntryMutationRequest request) {
        String tenantId = tenantId();
        DatasetEntryData data = new DatasetEntryData(
                entryId, request.sequence(), request.input(), request.expectedOutput(),
                request.labels(), request.metadata());
        Instant now = clock.instant();
        DatasetWriteResult<VersionedDatasetEntry> result = datasets.putEntry(
                tenantId, datasetId, request.expectedVersion(), data, now);
        VersionedDatasetEntry entry = resolve(result);
        createSnapshot(tenantId, datasetId, result.currentVersion(), now);
        return DatasetEntryMutationResponse.from(entry, result.currentVersion());
    }

    private void createSnapshot(String tenantId, String datasetId, long version, Instant now) {
        DatasetWriteResult<DatasetSnapshot> snapshot = datasets.createSnapshot(
                tenantId, datasetId, version, actor(), now);
        resolve(snapshot);
    }

    private DatasetImportError appendError(
            String tenantId, String jobId, int rowNumber, String code, String message,
            DatasetImportEntryRequest row) {
        return datasets.appendImportError(
                tenantId, UUID.randomUUID().toString(), jobId, rowNumber, code,
                message == null || message.isBlank() ? "Import row is invalid" : message,
                rawRecord(row), clock.instant());
    }

    private DatasetEntryData toEntry(DatasetImportEntryRequest row) {
        if (row == null) throw new IllegalArgumentException("row must not be null");
        return new DatasetEntryData(
                row.id(), row.sequence() == null ? -1 : row.sequence(), row.input(), row.expectedOutput(),
                row.labels() == null ? Map.of() : row.labels(),
                row.metadata() == null ? Map.of() : row.metadata());
    }

    private String rawRecord(DatasetImportEntryRequest row) {
        try {
            return objectMapper.writeValueAsString(row);
        } catch (JsonProcessingException exception) {
            return "{\"unavailable\":true}";
        }
    }

    private VersionedDataset requireDataset(String tenantId, String datasetId) {
        return datasets.findDataset(tenantId, datasetId)
                .orElseThrow(DatasetVersioningException::datasetNotFound);
    }

    private static <T> T resolve(DatasetWriteResult<T> result) {
        return switch (result.status()) {
            case APPLIED -> result.value();
            case NOT_FOUND -> throw DatasetVersioningException.datasetNotFound();
            case VERSION_CONFLICT -> throw DatasetVersioningException.versionConflict(result.currentVersion());
        };
    }

    private static DatasetImportResponse response(DatasetImportJob job, List<DatasetImportError> errors) {
        return new DatasetImportResponse(
                DatasetImportJobResponse.from(job),
                errors.stream().map(DatasetImportErrorResponse::from).toList());
    }

    private static String tenantId() {
        return TenantContext.currentTenantId();
    }

    private static String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof XnlpPrincipal principal) {
            return principal.subject();
        }
        return "system";
    }
}
