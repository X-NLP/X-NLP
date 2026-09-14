package com.xnlp.server.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xnlp.core.config.ModelConfig;
import com.xnlp.core.config.ModelProtocol;
import com.xnlp.core.config.ModelSource;
import com.xnlp.core.config.ModelType;
import com.xnlp.core.repository.ModelConfigRepository;
import com.xnlp.server.tenant.TenantContext;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC-backed model profile repository.
 *
 * <p>The repository deliberately uses Spring Boot's JDBC auto-configuration
 * rather than a vendor-specific ORM.  Switching MySQL, PostgreSQL, or H2 is
 * therefore only a matter of changing the active datasource profile.
 */
@Repository
@Primary
@Profile("!file")
public class JdbcModelConfigRepository implements ModelConfigRepository {

    private static final String SELECT_COLUMNS = "name, type, protocol, source, provider, model_name, "
            + "base_url, api_key, version, model_path, backend, device, max_input_length, "
            + "max_output_length, options_json, created_at, updated_at";

    private final JdbcTemplate jdbc;
    private final ObjectMapper jsonMapper;

    public JdbcModelConfigRepository(JdbcTemplate jdbc, ObjectMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public List<ModelConfig> findAll() {
        return jdbc.query("SELECT " + SELECT_COLUMNS + " FROM model_config WHERE tenant_id = ? ORDER BY name",
                this::mapRow, TenantContext.currentTenantId());
    }

    @Override
    public Optional<ModelConfig> findByName(String name) {
        return jdbc.query("SELECT " + SELECT_COLUMNS + " FROM model_config WHERE name = ? AND tenant_id = ?",
                this::mapRow, storageName(name), TenantContext.currentTenantId())
                .stream().findFirst();
    }

    @Override
    public ModelConfig save(ModelConfig config) {
        String optionsJson = toJson(config.getOptions());
        int updated = jdbc.update("""
                UPDATE model_config SET type = ?, protocol = ?, source = ?, provider = ?, model_name = ?,
                    base_url = ?, api_key = ?, version = ?, model_path = ?, backend = ?, device = ?,
                    max_input_length = ?, max_output_length = ?, options_json = ?, updated_at = ?
                WHERE name = ? AND tenant_id = ?
                """, config.getType() == null ? null : config.getType().name(),
                config.getProtocol() == null ? null : config.getProtocol().name(),
                config.getSource() == null ? null : config.getSource().name(), config.getProvider(),
                config.getModelName(), config.getBaseUrl(), config.getApiKey(), config.getVersion(),
                config.getModelPath(), config.getBackend(), config.getDevice(), config.getMaxInputLength(),
                config.getMaxOutputLength(), optionsJson, Timestamp.from(Instant.now()), storageName(config.getName()),
                TenantContext.currentTenantId());
        if (updated == 0) {
            Instant now = Instant.now();
            jdbc.update("""
                    INSERT INTO model_config (name, tenant_id, type, protocol, source, provider, model_name, base_url,
                        api_key, version, model_path, backend, device, max_input_length, max_output_length,
                        options_json, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, storageName(config.getName()), TenantContext.currentTenantId(), config.getType() == null ? null : config.getType().name(),
                    config.getProtocol() == null ? null : config.getProtocol().name(),
                    config.getSource() == null ? null : config.getSource().name(), config.getProvider(),
                    config.getModelName(), config.getBaseUrl(), config.getApiKey(), config.getVersion(),
                    config.getModelPath(), config.getBackend(), config.getDevice(), config.getMaxInputLength(),
                    config.getMaxOutputLength(), optionsJson, Timestamp.from(now), Timestamp.from(now));
        }
        return config;
    }

    @Override
    public void deleteByName(String name) {
        jdbc.update("DELETE FROM model_config WHERE name = ? AND tenant_id = ?",
                storageName(name), TenantContext.currentTenantId());
    }

    private ModelConfig mapRow(ResultSet rs, int rowNum) throws SQLException {
        ModelConfig config = new ModelConfig();
        config.setName(logicalName(rs.getString("name")));
        config.setType(enumValue(ModelType.class, rs.getString("type")));
        config.setProtocol(enumValue(ModelProtocol.class, rs.getString("protocol")));
        config.setSource(enumValue(ModelSource.class, rs.getString("source")));
        config.setProvider(rs.getString("provider"));
        config.setModelName(rs.getString("model_name"));
        config.setBaseUrl(rs.getString("base_url"));
        config.setApiKey(rs.getString("api_key"));
        config.setVersion(rs.getString("version"));
        config.setModelPath(rs.getString("model_path"));
        config.setBackend(rs.getString("backend"));
        config.setDevice(rs.getString("device"));
        Integer maxInputLength = rs.getObject("max_input_length", Integer.class);
        Integer maxOutputLength = rs.getObject("max_output_length", Integer.class);
        config.setMaxInputLength(maxInputLength == null ? 512 : maxInputLength);
        config.setMaxOutputLength(maxOutputLength == null ? 256 : maxOutputLength);
        config.setOptions(fromJson(rs.getString("options_json")));
        return config;
    }

    private String storageName(String logicalName) {
        String tenantId = TenantContext.currentTenantId();
        return TenantContext.DEFAULT_TENANT_ID.equals(tenantId)
                ? logicalName : tenantId + "::" + logicalName;
    }

    private String logicalName(String storedName) {
        String prefix = TenantContext.currentTenantId() + "::";
        return storedName != null && storedName.startsWith(prefix)
                ? storedName.substring(prefix.length()) : storedName;
    }

    private String toJson(Map<String, Object> value) {
        if (value == null || value.isEmpty()) return "{}";
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Model options cannot be serialized", e);
        }
    }

    private Map<String, Object> fromJson(String value) throws SQLException {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return jsonMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new SQLException("Invalid model options JSON", e);
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
