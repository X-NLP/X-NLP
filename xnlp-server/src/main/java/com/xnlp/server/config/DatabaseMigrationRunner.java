package com.xnlp.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies portable, ordered schema migrations without binding repositories to a
 * vendor-specific SQL dialect.
 *
 * <p>Spring Boot's SQL initializer is intentionally disabled for the server.
 * The runner owns schema creation so that existing installations get a durable
 * migration history instead of relying on {@code CREATE TABLE IF NOT EXISTS}
 * alone. Every migration is idempotent: if a process stops after DDL succeeds
 * but before the history row is written, the next startup can safely retry it.</p>
 */
@Component
@Profile("!memory")
@EnableConfigurationProperties(DatabaseMigrationProperties.class)
public class DatabaseMigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationRunner.class);
    private static final String HISTORY_TABLE = "xnlp_schema_history";
    private static final String BASELINE_RESOURCE = "db/migration/V1__baseline.sql";
    private static final String RAG_STORAGE_RESOURCE = "db/migration/V5__rag-storage.sql";
    private static final String RETRIEVAL_EVALUATION_RESOURCE = "db/migration/V7__retrieval-evaluation.sql";
    private static final String IDENTITY_RBAC_RESOURCE = "db/migration/V8__identity-rbac.sql";
    private static final String API_KEY_AUDIT_RESOURCE = "db/migration/V9__api-key-audit.sql";
    private static final String TENANT_QUOTA_RESOURCE = "db/migration/V10__tenant-quota.sql";
    private static final String DATASET_VERSIONING_RESOURCE = "db/migration/V11__dataset-versioning.sql";
    private static final String RESUMABLE_EVALUATION_RESOURCE = "db/migration/V12__resumable-evaluation.sql";

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final DatabaseMigrationProperties properties;

    public DatabaseMigrationRunner(
            JdbcTemplate jdbc,
            DataSource dataSource,
            DatabaseMigrationProperties properties) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.properties = properties;
        migrate();
    }

    private void migrate() {
        if (!properties.isEnabled()) {
            log.warn("Database migrations are disabled by xnlp.database.migration.enabled=false");
            return;
        }

        try {
            createHistoryTable();
            List<MigrationDefinition> migrations = migrations();
            Map<Integer, AppliedMigration> applied = loadAppliedMigrations();
            validateAppliedMigrations(migrations, applied);

            for (MigrationDefinition migration : migrations) {
                if (applied.containsKey(migration.version())) {
                    continue;
                }
                applyMigration(migration);
            }
            log.info("Database schema is at migration version {}", migrations.getLast().version());
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Failed to migrate the X-NLP database", ex);
        }
    }

    private void createHistoryTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS xnlp_schema_history (
                    version INTEGER PRIMARY KEY,
                    description VARCHAR(255) NOT NULL,
                    checksum VARCHAR(64) NOT NULL,
                    applied_at TIMESTAMP NOT NULL,
                    execution_ms BIGINT NOT NULL
                )
                """);
    }

    private List<MigrationDefinition> migrations() {
        return List.of(
                new MigrationDefinition(
                        1,
                        "baseline",
                        checksum(1, "baseline", readResource(BASELINE_RESOURCE)),
                        this::createBaseline),
                new MigrationDefinition(
                        2,
                        "evaluation-progress-columns",
                        checksum(2, "evaluation-progress-columns", "total_entries|processed_entries|progress_percent|cancel_requested"),
                        this::ensureEvaluationRunColumns),
                new MigrationDefinition(
                        3,
                        "waste-weighing-trip-number",
                        checksum(3, "waste-weighing-trip-number", "trip_no"),
                        this::ensureWasteWeighingColumns),
                new MigrationDefinition(
                        4,
                        "multi-tenant-isolation",
                        checksum(4, "multi-tenant-isolation",
                                "tenant_id|model_config|datasets|dataset_entries|evaluation_runs|waste_vehicles|waste_applications|waste_audits|waste_weighings"),
                        this::ensureTenantColumns),
                new MigrationDefinition(
                        5,
                        "rag-storage",
                        checksum(5, "rag-storage", readResource(RAG_STORAGE_RESOURCE)
                                + "|knowledge_documents_kb:tenant_id,knowledge_base_id,updated_at"
                                + "|knowledge_chunks_document:tenant_id,knowledge_base_id,document_id,seq"
                                + "|knowledge_embeddings_search:tenant_id,knowledge_base_id,embedding_model"
                                + "|ingestion_jobs_kb:tenant_id,knowledge_base_id,created_at"),
                        this::createRagStorage),
                new MigrationDefinition(
                        6,
                        "ingestion-control-and-rag-constraints",
                        checksum(6, "ingestion-control-and-rag-constraints",
                                "ingestion_jobs.cancel_requested|knowledge_bases_name_unique|knowledge_documents_external_unique"),
                        this::upgradeIngestionControl),
                new MigrationDefinition(
                        7,
                        "retrieval-evaluation",
                        checksum(7, "retrieval-evaluation", readResource(RETRIEVAL_EVALUATION_RESOURCE)
                                + "|retrieval_evaluation_runs_kb:tenant_id,knowledge_base_id,created_at"
                                + "|retrieval_evaluation_samples_run:tenant_id,knowledge_base_id,run_id,seq"),
                        this::createRetrievalEvaluationStorage),
                new MigrationDefinition(
                        8,
                        "identity-rbac",
                        checksum(8, "identity-rbac", readResource(IDENTITY_RBAC_RESOURCE)
                                + "|tenant_memberships_subject:subject,tenant_id"),
                        this::createIdentityRbacStorage),
                new MigrationDefinition(
                        9,
                        "api-key-audit",
                        checksum(9, "api-key-audit", readResource(API_KEY_AUDIT_RESOURCE)
                                + "|api_keys_tenant:tenant_id,created_at"
                                + "|api_keys_hash:secret_hash"
                                + "|audit_events_tenant_time:tenant_id,occurred_at"
                                + "|audit_events_tenant_action:tenant_id,action,occurred_at"),
                        this::createApiKeyAuditStorage),
                new MigrationDefinition(
                        10,
                        "tenant-quota",
                        checksum(10, "tenant-quota", readResource(TENANT_QUOTA_RESOURCE)),
                        this::createTenantQuotaStorage),
                new MigrationDefinition(
                        11,
                        "dataset-versioning",
                        checksum(11, "dataset-versioning", readResource(DATASET_VERSIONING_RESOURCE)),
                        this::createDatasetVersioningStorage),
                new MigrationDefinition(
                        12,
                        "resumable-evaluation",
                        checksum(12, "resumable-evaluation", readResource(RESUMABLE_EVALUATION_RESOURCE)),
                        this::createResumableEvaluationStorage)
        );
    }

    private Map<Integer, AppliedMigration> loadAppliedMigrations() {
        return jdbc.query(
                "SELECT version, description, checksum FROM " + HISTORY_TABLE + " ORDER BY version",
                resultSet -> {
                    Map<Integer, AppliedMigration> result = new LinkedHashMap<>();
                    while (resultSet.next()) {
                        AppliedMigration migration = new AppliedMigration(
                                resultSet.getInt("version"),
                                resultSet.getString("description"),
                                resultSet.getString("checksum"));
                        result.put(migration.version(), migration);
                    }
                    return result;
                });
    }

    private void validateAppliedMigrations(
            List<MigrationDefinition> migrations,
            Map<Integer, AppliedMigration> applied) {
        Map<Integer, MigrationDefinition> known = migrations.stream()
                .collect(java.util.stream.Collectors.toMap(MigrationDefinition::version, migration -> migration));

        for (AppliedMigration existing : applied.values()) {
            MigrationDefinition expected = known.get(existing.version());
            if (expected == null) {
                throw new IllegalStateException("Database contains unknown migration version " + existing.version());
            }
            if (!expected.description().equals(existing.description())) {
                throw new IllegalStateException("Migration description mismatch for version " + existing.version());
            }
            if (properties.isValidateChecksums() && !expected.checksum().equals(existing.checksum())) {
                throw new IllegalStateException("Migration checksum mismatch for version " + existing.version()
                        + "; restore the matching application version or disable checksum validation explicitly");
            }
        }
    }

    private void applyMigration(MigrationDefinition migration) {
        Instant started = Instant.now();
        log.info("Applying database migration V{}__{}", migration.version(), migration.description());
        try {
            migration.action().apply();
            long elapsedMillis = Duration.between(started, Instant.now()).toMillis();
            jdbc.update(
                    "INSERT INTO " + HISTORY_TABLE
                            + " (version, description, checksum, applied_at, execution_ms) VALUES (?, ?, ?, ?, ?)",
                    migration.version(),
                    migration.description(),
                    migration.checksum(),
                    Timestamp.from(Instant.now()),
                    elapsedMillis);
            log.info("Applied database migration V{}__{} in {} ms", migration.version(), migration.description(), elapsedMillis);
        } catch (Exception ex) {
            throw new IllegalStateException("Migration V" + migration.version() + "__" + migration.description() + " failed", ex);
        }
    }

    private void createBaseline() {
        String script = readResource(BASELINE_RESOURCE);
        script = renderBaselineSql(script, databaseProductName());
        new ResourceDatabasePopulator(new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)))
                .execute(dataSource);
    }

    static String renderBaselineSql(String script, String databaseProductName) {
        if (databaseProductName.contains("postgresql")) {
            return script.replaceAll("\\bDOUBLE\\b(?!\\s+PRECISION)", "DOUBLE PRECISION");
        }
        return script;
    }

    private void createRagStorage() {
        String script = readResource(RAG_STORAGE_RESOURCE)
                .replace("__LARGE_TEXT__", largeTextType());
        new ResourceDatabasePopulator(new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)))
                .execute(dataSource);
        ensureIndex("knowledge_documents", "knowledge_documents_kb", """
                CREATE INDEX knowledge_documents_kb
                ON knowledge_documents (tenant_id, knowledge_base_id, updated_at)
                """);
        ensureIndex("knowledge_chunks", "knowledge_chunks_document", """
                CREATE INDEX knowledge_chunks_document
                ON knowledge_chunks (tenant_id, knowledge_base_id, document_id, seq)
                """);
        ensureIndex("knowledge_embeddings", "knowledge_embeddings_search", """
                CREATE INDEX knowledge_embeddings_search
                ON knowledge_embeddings (tenant_id, knowledge_base_id, embedding_model)
                """);
        ensureIndex("ingestion_jobs", "ingestion_jobs_kb", """
                CREATE INDEX ingestion_jobs_kb
                ON ingestion_jobs (tenant_id, knowledge_base_id, created_at)
                """);
    }

    private void createRetrievalEvaluationStorage() {
        String script = readResource(RETRIEVAL_EVALUATION_RESOURCE)
                .replace("__LARGE_TEXT__", largeTextType());
        new ResourceDatabasePopulator(new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)))
                .execute(dataSource);
        ensureIndex("retrieval_evaluation_runs", "retrieval_evaluation_runs_kb", """
                CREATE INDEX retrieval_evaluation_runs_kb
                ON retrieval_evaluation_runs (tenant_id, knowledge_base_id, created_at)
                """);
        ensureIndex("retrieval_evaluation_samples", "retrieval_evaluation_samples_run", """
                CREATE INDEX retrieval_evaluation_samples_run
                ON retrieval_evaluation_samples (tenant_id, knowledge_base_id, run_id, seq)
                """);
    }

    private void createIdentityRbacStorage() {
        new ResourceDatabasePopulator(new ClassPathResource(IDENTITY_RBAC_RESOURCE)).execute(dataSource);
        ensureIndex("tenant_memberships", "tenant_memberships_subject", """
                CREATE INDEX tenant_memberships_subject
                ON tenant_memberships (subject, tenant_id)
                """);
        Instant now = Instant.now();
        try {
            jdbc.update("INSERT INTO tenants (id, display_name, created_at, updated_at) VALUES (?, ?, ?, ?)",
                    com.xnlp.server.tenant.TenantContext.DEFAULT_TENANT_ID,
                    "Default tenant", Timestamp.from(now), Timestamp.from(now));
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            // Existing installation already has the default tenant.
        }
    }

    private void createApiKeyAuditStorage() {
        String script = readResource(API_KEY_AUDIT_RESOURCE)
                .replace("__LARGE_TEXT__", largeTextType());
        new ResourceDatabasePopulator(new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)))
                .execute(dataSource);
        ensureIndex("api_keys", "api_keys_tenant", """
                CREATE INDEX api_keys_tenant ON api_keys (tenant_id, created_at)
                """);
        ensureIndex("api_keys", "api_keys_hash", """
                CREATE UNIQUE INDEX api_keys_hash ON api_keys (secret_hash)
                """);
        ensureIndex("audit_events", "audit_events_tenant_time", """
                CREATE INDEX audit_events_tenant_time ON audit_events (tenant_id, occurred_at)
                """);
        ensureIndex("audit_events", "audit_events_tenant_action", """
                CREATE INDEX audit_events_tenant_action ON audit_events (tenant_id, action, occurred_at)
                """);
    }

    private void createTenantQuotaStorage() {
        new ResourceDatabasePopulator(new ClassPathResource(TENANT_QUOTA_RESOURCE)).execute(dataSource);
    }

    private void createDatasetVersioningStorage() {
        String script = readResource(DATASET_VERSIONING_RESOURCE)
                .replace("__LARGE_TEXT__", largeTextType());
        new ResourceDatabasePopulator(new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)))
                .execute(dataSource);
    }

    private void createResumableEvaluationStorage() {
        ensureEvaluationRecoveryColumns();
        String script = readResource(RESUMABLE_EVALUATION_RESOURCE)
                .replace("__LARGE_TEXT__", largeTextType());
        new ResourceDatabasePopulator(new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)))
                .execute(dataSource);
    }

    private void ensureEvaluationRecoveryColumns() {
        ensureColumns("evaluation_runs", List.of(
                new ColumnDefinition("dataset_version", "BIGINT"),
                new ColumnDefinition("parent_run_id", "VARCHAR(64)"),
                new ColumnDefinition("root_run_id", "VARCHAR(64)"),
                new ColumnDefinition("attempt_no", "INTEGER NOT NULL DEFAULT 0"),
                new ColumnDefinition("retry_failed_only", "BOOLEAN NOT NULL DEFAULT FALSE")));
    }

    private void upgradeIngestionControl() {
        ensureColumns("ingestion_jobs", List.of(
                new ColumnDefinition("cancel_requested", "BOOLEAN NOT NULL DEFAULT FALSE")));
        ensureIndex("knowledge_bases", "knowledge_bases_name_unique", """
                CREATE UNIQUE INDEX knowledge_bases_name_unique
                ON knowledge_bases (tenant_id, name)
                """);
        ensureIndex("knowledge_documents", "knowledge_documents_external_unique", """
                CREATE UNIQUE INDEX knowledge_documents_external_unique
                ON knowledge_documents (tenant_id, knowledge_base_id, external_id)
                """);
    }

    private String largeTextType() {
        return databaseProductName().contains("mysql") ? "LONGTEXT" : "TEXT";
    }

    private String databaseProductName() {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            return product == null ? "" : product.toLowerCase(java.util.Locale.ROOT);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to detect database capabilities", ex);
        }
    }

    private void ensureIndex(String table, String indexName, String createSql) {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            if (hasIndex(metadata, connection, table, indexName)) {
                return;
            }
            jdbc.execute(createSql);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to create index " + indexName, ex);
        }
    }

    private boolean hasIndex(DatabaseMetaData metadata, Connection connection, String table, String indexName)
            throws SQLException {
        String catalog = connection.getCatalog();
        String schema = connection.getSchema();
        for (String tablePattern : List.of(table, table.toUpperCase(), table.toLowerCase())) {
            try (ResultSet indexes = metadata.getIndexInfo(catalog, schema, tablePattern, false, false)) {
                while (indexes.next()) {
                    String existing = indexes.getString("INDEX_NAME");
                    if (existing != null && existing.equalsIgnoreCase(indexName)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void ensureEvaluationRunColumns() {
        List<ColumnDefinition> columns = List.of(
                new ColumnDefinition("total_entries", "INTEGER NOT NULL DEFAULT 0"),
                new ColumnDefinition("processed_entries", "INTEGER NOT NULL DEFAULT 0"),
                new ColumnDefinition("progress_percent", "DOUBLE PRECISION NOT NULL DEFAULT 0"),
                new ColumnDefinition("cancel_requested", "BOOLEAN NOT NULL DEFAULT FALSE")
        );
        ensureColumns("evaluation_runs", columns);
    }

    private void ensureWasteWeighingColumns() {
        ensureColumns("waste_weighings", List.of(
                new ColumnDefinition("trip_no", "INTEGER NOT NULL DEFAULT 1")));
    }

    private void ensureTenantColumns() {
        List<String> tables = List.of(
                "model_config", "datasets", "dataset_entries", "evaluation_runs",
                "waste_vehicles", "waste_applications", "waste_audits", "waste_weighings");
        for (String table : tables) {
            ensureColumns(table, List.of(
                    new ColumnDefinition("tenant_id", "VARCHAR(64) DEFAULT 'default'")));
            jdbc.update("UPDATE " + table
                    + " SET tenant_id = ? WHERE tenant_id IS NULL OR tenant_id = ''",
                    com.xnlp.server.tenant.TenantContext.DEFAULT_TENANT_ID);
        }
    }

    private void ensureColumns(String table, List<ColumnDefinition> columns) {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            for (ColumnDefinition column : columns) {
                if (!hasColumn(metadata, connection, table, column.name())) {
                    jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column.name() + " " + column.definition());
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to upgrade table " + table, ex);
        }
    }

    private boolean hasColumn(DatabaseMetaData metadata, Connection connection, String table, String column)
            throws SQLException {
        String catalog = connection.getCatalog();
        String schema = connection.getSchema();
        for (String tablePattern : List.of(table, table.toUpperCase(), table.toLowerCase())) {
            for (String columnPattern : List.of(column, column.toUpperCase(), column.toLowerCase())) {
                try (ResultSet columns = metadata.getColumns(catalog, schema, tablePattern, columnPattern)) {
                    if (columns.next()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private String readResource(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot read database migration resource " + path, ex);
        }
    }

    private String checksum(int version, String description, String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] input = (version + "|" + description + "|" + content).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(digest.digest(input));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private record MigrationDefinition(
            int version,
            String description,
            String checksum,
            MigrationAction action) {
    }

    @FunctionalInterface
    private interface MigrationAction {
        void apply() throws Exception;
    }

    private record AppliedMigration(int version, String description, String checksum) {
    }

    private record ColumnDefinition(String name, String definition) {
    }
}
