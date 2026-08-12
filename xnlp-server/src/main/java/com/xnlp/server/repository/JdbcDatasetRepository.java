package com.xnlp.server.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xnlp.core.eval.EvaluationDataset;
import com.xnlp.core.eval.EvaluationEntry;
import com.xnlp.core.eval.NLPTaskType;
import com.xnlp.core.repository.DatasetRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Spring JDBC implementation for datasets and their entries. */
@Repository
@Primary
@Profile("!file")
public class JdbcDatasetRepository implements DatasetRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper jsonMapper;

    public JdbcDatasetRepository(JdbcTemplate jdbc, ObjectMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public List<EvaluationDataset> findAll() {
        return jdbc.query("""
                SELECT id, name, description, task_type, entry_count, created_at, updated_at
                FROM datasets ORDER BY updated_at DESC, name
                """, this::mapDataset);
    }

    @Override
    public Optional<EvaluationDataset> findById(String id) {
        List<EvaluationDataset> datasets = jdbc.query("""
                SELECT id, name, description, task_type, entry_count, created_at, updated_at
                FROM datasets WHERE id = ?
                """, this::mapDataset, id);
        if (datasets.isEmpty()) return Optional.empty();
        EvaluationDataset dataset = datasets.getFirst();
        dataset.setEntries(loadEntries(id));
        dataset.setEntryCount(dataset.getEntries().size());
        return Optional.of(dataset);
    }

    @Override
    @Transactional
    public EvaluationDataset save(EvaluationDataset dataset) {
        List<EvaluationEntry> entries = dataset.getEntries() == null ? List.of() : dataset.getEntries();
        dataset.setEntryCount(entries.size());
        Instant now = dataset.getUpdatedAt() == null ? Instant.now() : dataset.getUpdatedAt();
        if (dataset.getCreatedAt() == null) dataset.setCreatedAt(now);
        dataset.setUpdatedAt(now);

        int updated = jdbc.update("""
                UPDATE datasets SET name = ?, description = ?, task_type = ?, entry_count = ?,
                    created_at = ?, updated_at = ? WHERE id = ?
                """, dataset.getName(), dataset.getDescription(),
                dataset.getTaskType() == null ? null : dataset.getTaskType().name(), entries.size(),
                Timestamp.from(dataset.getCreatedAt()), Timestamp.from(dataset.getUpdatedAt()), dataset.getId());
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO datasets (id, name, description, task_type, entry_count, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, dataset.getId(), dataset.getName(), dataset.getDescription(),
                    dataset.getTaskType() == null ? null : dataset.getTaskType().name(), entries.size(),
                    Timestamp.from(dataset.getCreatedAt()), Timestamp.from(dataset.getUpdatedAt()));
        }
        jdbc.update("DELETE FROM dataset_entries WHERE dataset_id = ?", dataset.getId());
        for (int i = 0; i < entries.size(); i++) {
            EvaluationEntry entry = entries.get(i);
            String entryId = entry.getId() == null || entry.getId().isBlank()
                    ? UUID.randomUUID().toString() : entry.getId();
            entry.setId(entryId);
            jdbc.update("""
                    INSERT INTO dataset_entries (id, dataset_id, seq, input_text, expected_output, labels_json, metadata_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, entryId, dataset.getId(), i, entry.getInput(), entry.getExpectedOutput(),
                    toJson(entry.getLabels()), toJson(entry.getMetadata()));
        }
        return dataset;
    }

    @Override
    @Transactional
    public void deleteById(String id) {
        jdbc.update("DELETE FROM dataset_entries WHERE dataset_id = ?", id);
        jdbc.update("DELETE FROM datasets WHERE id = ?", id);
    }

    @Override
    public int count() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM datasets", Integer.class);
        return count == null ? 0 : count;
    }

    private EvaluationDataset mapDataset(ResultSet rs, int rowNum) throws SQLException {
        EvaluationDataset dataset = new EvaluationDataset();
        dataset.setId(rs.getString("id"));
        dataset.setName(rs.getString("name"));
        dataset.setDescription(rs.getString("description"));
        String taskType = rs.getString("task_type");
        if (taskType != null) dataset.setTaskType(NLPTaskType.valueOf(taskType));
        dataset.setEntryCount(rs.getInt("entry_count"));
        dataset.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        dataset.setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));
        return dataset;
    }

    private List<EvaluationEntry> loadEntries(String datasetId) {
        return jdbc.query("""
                SELECT id, input_text, expected_output, labels_json, metadata_json
                FROM dataset_entries WHERE dataset_id = ? ORDER BY seq
                """, (rs, rowNum) -> {
            EvaluationEntry entry = new EvaluationEntry();
            entry.setId(rs.getString("id"));
            entry.setInput(rs.getString("input_text"));
            entry.setExpectedOutput(rs.getString("expected_output"));
            entry.setLabels(fromJson(rs.getString("labels_json")));
            entry.setMetadata(fromJson(rs.getString("metadata_json")));
            return entry;
        }, datasetId);
    }

    private String toJson(Map<String, Object> value) {
        if (value == null || value.isEmpty()) return "{}";
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Dataset entry metadata cannot be serialized", e);
        }
    }

    private Map<String, Object> fromJson(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return jsonMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid dataset entry JSON", e);
        }
    }

    private static Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC).toInstant();
    }
}
