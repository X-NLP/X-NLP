package com.xnlp.server.dataset.versioning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Repository
@Profile("!memory")
public class JdbcDatasetVersionRepository implements DatasetVersionRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcDatasetVersionRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<VersionedDataset> findDataset(String tenantId, String datasetId) {
        List<VersionedDataset> values = jdbc.query("""
                SELECT tenant_id, id, name, description, task_type, version, entry_count, created_at, updated_at
                FROM versioned_datasets WHERE tenant_id = ? AND id = ?
                """, this::mapDataset, tenant(tenantId), id(datasetId, "datasetId"));
        return values.stream().findFirst();
    }

    @Override
    public VersionedDataset createDataset(
            String tenantId, String datasetId, DatasetMetadata metadata, Instant now) {
        String normalizedTenant = tenant(tenantId);
        String normalizedId = id(datasetId, "datasetId");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(now, "now");
        ensureTenant(normalizedTenant, now);
        jdbc.update("""
                INSERT INTO versioned_datasets
                    (tenant_id, id, name, description, task_type, version, entry_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 0, 0, ?, ?)
                """, normalizedTenant, normalizedId, metadata.name(), metadata.description(), metadata.taskType(),
                timestamp(now), timestamp(now));
        return findDataset(normalizedTenant, normalizedId).orElseThrow();
    }

    @Override
    @Transactional
    public VersionedDataset bootstrapDataset(
            String tenantId, String datasetId, DatasetMetadata metadata, List<DatasetEntryData> initialEntries,
            String createdBy, Instant createdAt, Instant updatedAt) {
        String normalizedTenant = tenant(tenantId);
        String normalizedDataset = id(datasetId, "datasetId");
        Objects.requireNonNull(metadata, "metadata");
        List<DatasetEntryData> values = List.copyOf(Objects.requireNonNull(initialEntries, "entries"));
        String actor = DatasetVersioningSupport.text(createdBy, "createdBy", 190);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        validateDistinctEntryIds(values);
        VersionedDataset existing = findDataset(normalizedTenant, normalizedDataset).orElse(null);
        if (existing != null) return existing;
        ensureTenant(normalizedTenant, createdAt);
        boolean inserted = insertDatasetIfAbsent(normalizedTenant, normalizedDataset, metadata, values.size(),
                createdAt, updatedAt);
        if (!inserted) return findDataset(normalizedTenant, normalizedDataset).orElseThrow();
        for (DatasetEntryData entry : values) {
            jdbc.update("""
                    INSERT INTO versioned_dataset_entries
                        (tenant_id, dataset_id, id, seq, input_text, expected_output, labels_json, metadata_json,
                         created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, normalizedTenant, normalizedDataset, entry.id(), entry.sequence(), entry.input(),
                    entry.expectedOutput(), toJson(entry.labels()), toJson(entry.metadata()),
                    timestamp(createdAt), timestamp(updatedAt));
        }
        insertSnapshot(normalizedTenant, normalizedDataset, 0, metadata, values, actor, updatedAt);
        return findDataset(normalizedTenant, normalizedDataset).orElseThrow();
    }

    @Override
    public DatasetWriteResult<VersionedDataset> updateDataset(
            String tenantId, String datasetId, long expectedVersion, DatasetMetadata metadata, Instant now) {
        validateVersion(expectedVersion);
        String normalizedTenant = tenant(tenantId);
        String normalizedId = id(datasetId, "datasetId");
        Objects.requireNonNull(metadata, "metadata");
        int updated = jdbc.update("""
                UPDATE versioned_datasets
                SET name = ?, description = ?, task_type = ?, version = version + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND version = ?
                """, metadata.name(), metadata.description(), metadata.taskType(), timestamp(now),
                normalizedTenant, normalizedId, expectedVersion);
        if (updated == 0) return failedWrite(normalizedTenant, normalizedId);
        VersionedDataset value = findDataset(normalizedTenant, normalizedId).orElseThrow();
        return DatasetWriteResult.applied(value, value.version());
    }

    @Override
    public List<VersionedDatasetEntry> findEntries(String tenantId, String datasetId) {
        return jdbc.query("""
                SELECT tenant_id, dataset_id, id, seq, input_text, expected_output,
                       labels_json, metadata_json, created_at, updated_at
                FROM versioned_dataset_entries WHERE tenant_id = ? AND dataset_id = ?
                ORDER BY seq, id
                """, this::mapEntry, tenant(tenantId), id(datasetId, "datasetId"));
    }

    @Override
    @Transactional
    public DatasetWriteResult<VersionedDatasetEntry> putEntry(
            String tenantId, String datasetId, long expectedVersion, DatasetEntryData entry, Instant now) {
        validateVersion(expectedVersion);
        String normalizedTenant = tenant(tenantId);
        String normalizedDataset = id(datasetId, "datasetId");
        Objects.requireNonNull(entry, "entry");
        if (!claimVersion(normalizedTenant, normalizedDataset, expectedVersion, now)) {
            return failedWrite(normalizedTenant, normalizedDataset);
        }
        Optional<VersionedDatasetEntry> existing = findEntry(normalizedTenant, normalizedDataset, entry.id());
        if (existing.isPresent()) {
            jdbc.update("""
                    UPDATE versioned_dataset_entries
                    SET seq = ?, input_text = ?, expected_output = ?, labels_json = ?, metadata_json = ?, updated_at = ?
                    WHERE tenant_id = ? AND dataset_id = ? AND id = ?
                    """, entry.sequence(), entry.input(), entry.expectedOutput(), toJson(entry.labels()),
                    toJson(entry.metadata()), timestamp(now), normalizedTenant, normalizedDataset, entry.id());
        } else {
            jdbc.update("""
                        INSERT INTO versioned_dataset_entries
                            (tenant_id, dataset_id, id, seq, input_text, expected_output,
                             labels_json, metadata_json, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, normalizedTenant, normalizedDataset, entry.id(), entry.sequence(), entry.input(),
                    entry.expectedOutput(), toJson(entry.labels()), toJson(entry.metadata()),
                    timestamp(now), timestamp(now));
        }
        int entryCount = countEntries(normalizedTenant, normalizedDataset);
        jdbc.update("UPDATE versioned_datasets SET entry_count = ? WHERE tenant_id = ? AND id = ?",
                entryCount, normalizedTenant, normalizedDataset);
        return DatasetWriteResult.applied(findEntry(normalizedTenant, normalizedDataset, entry.id()).orElseThrow(),
                expectedVersion + 1);
    }

    @Override
    @Transactional
    public DatasetWriteResult<VersionedDataset> deleteEntry(
            String tenantId, String datasetId, String entryId, long expectedVersion, Instant now) {
        validateVersion(expectedVersion);
        String normalizedTenant = tenant(tenantId);
        String normalizedDataset = id(datasetId, "datasetId");
        String normalizedEntry = id(entryId, "entryId");
        VersionedDataset current = findDataset(normalizedTenant, normalizedDataset).orElse(null);
        if (current == null) return DatasetWriteResult.notFound();
        if (current.version() != expectedVersion) return DatasetWriteResult.conflict(current.version());
        if (findEntry(normalizedTenant, normalizedDataset, normalizedEntry).isEmpty()) {
            return DatasetWriteResult.notFound();
        }
        if (!claimVersion(normalizedTenant, normalizedDataset, expectedVersion, now)) {
            return failedWrite(normalizedTenant, normalizedDataset);
        }
        jdbc.update("DELETE FROM versioned_dataset_entries WHERE tenant_id = ? AND dataset_id = ? AND id = ?",
                normalizedTenant, normalizedDataset, normalizedEntry);
        int entryCount = countEntries(normalizedTenant, normalizedDataset);
        jdbc.update("UPDATE versioned_datasets SET entry_count = ? WHERE tenant_id = ? AND id = ?",
                entryCount, normalizedTenant, normalizedDataset);
        VersionedDataset updated = findDataset(normalizedTenant, normalizedDataset).orElseThrow();
        return DatasetWriteResult.applied(updated, updated.version());
    }

    @Override
    @Transactional
    public DatasetWriteResult<DatasetSnapshot> createSnapshot(
            String tenantId, String datasetId, long expectedVersion, String createdBy, Instant now) {
        validateVersion(expectedVersion);
        String normalizedTenant = tenant(tenantId);
        String normalizedDataset = id(datasetId, "datasetId");
        VersionedDataset current = findDataset(normalizedTenant, normalizedDataset).orElse(null);
        if (current == null) return DatasetWriteResult.notFound();
        if (current.version() != expectedVersion) return DatasetWriteResult.conflict(current.version());
        DatasetSnapshot existing = findSnapshot(normalizedTenant, normalizedDataset, expectedVersion).orElse(null);
        if (existing != null) return DatasetWriteResult.applied(existing, current.version());
        String actor = DatasetVersioningSupport.text(createdBy, "createdBy", 190);
        List<DatasetEntryData> entries = findEntries(normalizedTenant, normalizedDataset).stream()
                .map(VersionedDatasetEntry::data).toList();
        try {
            insertSnapshot(normalizedTenant, normalizedDataset, expectedVersion, current.metadata(), entries,
                    actor, now);
        } catch (DuplicateKeyException ignored) {
            // Snapshot creation is idempotent for a dataset version.
        }
        return DatasetWriteResult.applied(
                findSnapshot(normalizedTenant, normalizedDataset, expectedVersion).orElseThrow(), current.version());
    }

    @Override
    public Optional<DatasetSnapshot> findSnapshot(String tenantId, String datasetId, long version) {
        validateVersion(version);
        List<DatasetSnapshot> values = jdbc.query("""
                SELECT tenant_id, dataset_id, version, name, description, task_type,
                       entry_count, entries_json, created_by, created_at
                FROM dataset_version_snapshots
                WHERE tenant_id = ? AND dataset_id = ? AND version = ?
                """, this::mapSnapshot, tenant(tenantId), id(datasetId, "datasetId"), version);
        return values.stream().findFirst();
    }

    @Override
    public List<DatasetSnapshot> findSnapshots(String tenantId, String datasetId) {
        return jdbc.query("""
                SELECT tenant_id, dataset_id, version, name, description, task_type,
                       entry_count, entries_json, created_by, created_at
                FROM dataset_version_snapshots
                WHERE tenant_id = ? AND dataset_id = ? ORDER BY version DESC
                """, this::mapSnapshot, tenant(tenantId), id(datasetId, "datasetId"));
    }

    @Override
    public DatasetImportJob createImportJob(
            String tenantId, String jobId, String datasetId, String sourceName,
            int totalRows, long expectedVersion, String createdBy, Instant now) {
        String normalizedTenant = tenant(tenantId);
        validateVersion(expectedVersion);
        if (totalRows < 0) throw new IllegalArgumentException("totalRows must not be negative");
        jdbc.update("""
                INSERT INTO dataset_import_jobs
                    (tenant_id, id, dataset_id, source_name, status, total_rows, imported_rows, failed_rows,
                     expected_version, resulting_version, error_summary, created_by, created_at, updated_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, 0, 0, ?, NULL, NULL, ?, ?, ?, NULL)
                """, normalizedTenant, id(jobId, "jobId"), id(datasetId, "datasetId"),
                DatasetVersioningSupport.text(sourceName, "sourceName", 512), DatasetImportStatus.PENDING.name(),
                totalRows, expectedVersion, DatasetVersioningSupport.text(createdBy, "createdBy", 190),
                timestamp(now), timestamp(now));
        return findImportJob(normalizedTenant, jobId).orElseThrow();
    }

    @Override
    public Optional<DatasetImportJob> findImportJob(String tenantId, String jobId) {
        List<DatasetImportJob> values = jdbc.query("""
                SELECT tenant_id, id, dataset_id, source_name, status, total_rows, imported_rows, failed_rows,
                       expected_version, resulting_version, error_summary, created_by, created_at, updated_at, completed_at
                FROM dataset_import_jobs WHERE tenant_id = ? AND id = ?
                """, this::mapJob, tenant(tenantId), id(jobId, "jobId"));
        return values.stream().findFirst();
    }

    @Override
    public boolean updateImportJob(
            String tenantId, String jobId, DatasetImportStatus status, int importedRows, int failedRows,
            Long resultingVersion, String errorSummary, Instant updatedAt, Instant completedAt) {
        Objects.requireNonNull(status, "status");
        DatasetImportJob current = findImportJob(tenantId, jobId).orElse(null);
        if (current == null || current.status().terminal()) return false;
        new DatasetImportJob(current.tenantId(), current.id(), current.datasetId(), current.sourceName(), status,
                current.totalRows(), importedRows, failedRows, current.expectedVersion(), resultingVersion,
                errorSummary, current.createdBy(), current.createdAt(), updatedAt, completedAt);
        return jdbc.update("""
                UPDATE dataset_import_jobs
                SET status = ?, imported_rows = ?, failed_rows = ?, resulting_version = ?, error_summary = ?,
                    updated_at = ?, completed_at = ?
                WHERE tenant_id = ? AND id = ?
                """, status.name(), importedRows, failedRows, resultingVersion,
                DatasetVersioningSupport.nullableText(errorSummary, 65535), timestamp(updatedAt),
                completedAt == null ? null : timestamp(completedAt), tenant(tenantId), id(jobId, "jobId")) == 1;
    }

    @Override
    public DatasetImportError appendImportError(
            String tenantId, String errorId, String jobId, int rowNumber, String errorCode,
            String message, String rawRecord, Instant now) {
        DatasetImportError error = new DatasetImportError(tenantId, errorId, jobId, rowNumber,
                errorCode, message, rawRecord, now);
        jdbc.update("""
                INSERT INTO dataset_import_errors
                    (tenant_id, id, job_id, row_number, error_code, error_message, raw_record, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, error.tenantId(), error.id(), error.jobId(), error.rowNumber(), error.errorCode(),
                error.message(), error.rawRecord(), timestamp(error.createdAt()));
        return error;
    }

    @Override
    public List<DatasetImportError> findImportErrors(String tenantId, String jobId, int limit, int offset) {
        if (limit < 1 || limit > 1000 || offset < 0) throw new IllegalArgumentException("Invalid pagination");
        return jdbc.query("""
                SELECT tenant_id, id, job_id, row_number, error_code, error_message, raw_record, created_at
                FROM dataset_import_errors WHERE tenant_id = ? AND job_id = ?
                ORDER BY row_number, id LIMIT ? OFFSET ?
                """, this::mapError, tenant(tenantId), id(jobId, "jobId"), limit, offset);
    }

    private Optional<VersionedDatasetEntry> findEntry(String tenantId, String datasetId, String entryId) {
        List<VersionedDatasetEntry> values = jdbc.query("""
                SELECT tenant_id, dataset_id, id, seq, input_text, expected_output,
                       labels_json, metadata_json, created_at, updated_at
                FROM versioned_dataset_entries WHERE tenant_id = ? AND dataset_id = ? AND id = ?
                """, this::mapEntry, tenantId, datasetId, id(entryId, "entryId"));
        return values.stream().findFirst();
    }

    private boolean claimVersion(String tenantId, String datasetId, long expectedVersion, Instant now) {
        return jdbc.update("""
                UPDATE versioned_datasets SET version = version + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND version = ?
                """, timestamp(now), tenantId, datasetId, expectedVersion) == 1;
    }

    private void insertSnapshot(
            String tenantId, String datasetId, long version, DatasetMetadata metadata,
            List<DatasetEntryData> entries, String createdBy, Instant createdAt) {
        String entriesJson = toJson(entries.stream().map(value -> Map.of(
                "id", value.id(),
                "sequence", value.sequence(),
                "input", value.input(),
                "expectedOutput", value.expectedOutput() == null ? "" : value.expectedOutput(),
                "labels", value.labels(),
                "metadata", value.metadata())).toList());
        jdbc.update("""
                INSERT INTO dataset_version_snapshots
                    (tenant_id, dataset_id, version, name, description, task_type,
                     entry_count, entries_json, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, datasetId, version, metadata.name(), metadata.description(), metadata.taskType(),
                entries.size(), entriesJson, createdBy, timestamp(createdAt));
    }

    private static void validateDistinctEntryIds(List<DatasetEntryData> entries) {
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (DatasetEntryData entry : entries) {
            Objects.requireNonNull(entry, "entries must not contain null");
            if (!ids.add(entry.id())) throw new IllegalArgumentException("Duplicate dataset entry id: " + entry.id());
        }
    }

    private <T> DatasetWriteResult<T> failedWrite(String tenantId, String datasetId) {
        return findDataset(tenantId, datasetId)
                .<DatasetWriteResult<T>>map(value -> DatasetWriteResult.conflict(value.version()))
                .orElseGet(DatasetWriteResult::notFound);
    }

    private int countEntries(String tenantId, String datasetId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM versioned_dataset_entries WHERE tenant_id = ? AND dataset_id = ?
                """, Integer.class, tenantId, datasetId);
        return count == null ? 0 : count;
    }

    private void ensureTenant(String tenantId, Instant now) {
        executeInsertIgnoringDuplicate(
                "INSERT INTO tenants (id, display_name, created_at, updated_at) VALUES (?, ?, ?, ?)",
                statement -> {
                    statement.setString(1, tenantId);
                    statement.setString(2, tenantId);
                    statement.setTimestamp(3, timestamp(now));
                    statement.setTimestamp(4, timestamp(now));
                });
    }

    private boolean insertDatasetIfAbsent(
            String tenantId, String datasetId, DatasetMetadata metadata, int entryCount,
            Instant createdAt, Instant updatedAt) {
        return executeInsertIgnoringDuplicate("""
                INSERT INTO versioned_datasets
                    (tenant_id, id, name, description, task_type, version, entry_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?)
                """, statement -> {
                    statement.setString(1, tenantId);
                    statement.setString(2, datasetId);
                    statement.setString(3, metadata.name());
                    statement.setString(4, metadata.description());
                    statement.setString(5, metadata.taskType());
                    statement.setInt(6, entryCount);
                    statement.setTimestamp(7, timestamp(createdAt));
                    statement.setTimestamp(8, timestamp(updatedAt));
                });
    }

    private boolean executeInsertIgnoringDuplicate(String sql, StatementBinder binder) {
        Boolean inserted = jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            boolean transactional = !connection.getAutoCommit();
            Savepoint savepoint = transactional ? connection.setSavepoint() : null;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                binder.bind(statement);
                statement.executeUpdate();
                if (savepoint != null) connection.releaseSavepoint(savepoint);
                return true;
            } catch (SQLException exception) {
                if (!duplicateKey(exception)) throw exception;
                if (savepoint != null) connection.rollback(savepoint);
                return false;
            }
        });
        return Boolean.TRUE.equals(inserted);
    }

    private static boolean duplicateKey(SQLException exception) {
        String state = exception.getSQLState();
        return "23505".equals(state) || state != null && state.startsWith("23")
                && (exception.getErrorCode() == 1062 || exception.getMessage().toLowerCase().contains("duplicate"));
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private VersionedDataset mapDataset(ResultSet rs, int rowNum) throws SQLException {
        return new VersionedDataset(rs.getString("tenant_id"), rs.getString("id"),
                new DatasetMetadata(rs.getString("name"), rs.getString("description"), rs.getString("task_type")),
                rs.getLong("version"), rs.getInt("entry_count"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private VersionedDatasetEntry mapEntry(ResultSet rs, int rowNum) throws SQLException {
        return new VersionedDatasetEntry(rs.getString("tenant_id"), rs.getString("dataset_id"),
                new DatasetEntryData(rs.getString("id"), rs.getInt("seq"), rs.getString("input_text"),
                        rs.getString("expected_output"), fromJsonMap(rs.getString("labels_json")),
                        fromJsonMap(rs.getString("metadata_json"))),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private DatasetSnapshot mapSnapshot(ResultSet rs, int rowNum) throws SQLException {
        String tenantId = rs.getString("tenant_id");
        String datasetId = rs.getString("dataset_id");
        Instant createdAt = instant(rs, "created_at");
        List<Map<String, Object>> serialized = fromJsonList(rs.getString("entries_json"));
        List<VersionedDatasetEntry> entries = serialized.stream().map(value -> new VersionedDatasetEntry(
                tenantId, datasetId, new DatasetEntryData((String) value.get("id"),
                ((Number) value.get("sequence")).intValue(), (String) value.get("input"),
                emptyToNull((String) value.get("expectedOutput")), mapValue(value.get("labels")),
                mapValue(value.get("metadata"))), createdAt, createdAt)).toList();
        return new DatasetSnapshot(tenantId, datasetId, rs.getLong("version"),
                new DatasetMetadata(rs.getString("name"), rs.getString("description"), rs.getString("task_type")),
                entries, rs.getString("created_by"), createdAt);
    }

    private DatasetImportJob mapJob(ResultSet rs, int rowNum) throws SQLException {
        long resultingVersion = rs.getLong("resulting_version");
        return new DatasetImportJob(rs.getString("tenant_id"), rs.getString("id"), rs.getString("dataset_id"),
                rs.getString("source_name"), DatasetImportStatus.valueOf(rs.getString("status")),
                rs.getInt("total_rows"), rs.getInt("imported_rows"), rs.getInt("failed_rows"),
                rs.getLong("expected_version"), rs.wasNull() ? null : resultingVersion,
                rs.getString("error_summary"), rs.getString("created_by"), instant(rs, "created_at"),
                instant(rs, "updated_at"), nullableInstant(rs, "completed_at"));
    }

    private DatasetImportError mapError(ResultSet rs, int rowNum) throws SQLException {
        return new DatasetImportError(rs.getString("tenant_id"), rs.getString("id"), rs.getString("job_id"),
                rs.getInt("row_number"), rs.getString("error_code"), rs.getString("error_message"),
                rs.getString("raw_record"), instant(rs, "created_at"));
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Dataset value cannot be serialized", exception);
        }
    }

    private Map<String, Object> fromJsonMap(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return json.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid dataset JSON", exception);
        }
    }

    private List<Map<String, Object>> fromJsonList(String value) {
        try {
            return json.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid dataset snapshot JSON", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String tenant(String value) {
        return DatasetVersioningSupport.tenantId(value);
    }

    private static String id(String value, String field) {
        return DatasetVersioningSupport.text(value, field, 64);
    }

    private static void validateVersion(long version) {
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(Objects.requireNonNull(value, "instant"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
