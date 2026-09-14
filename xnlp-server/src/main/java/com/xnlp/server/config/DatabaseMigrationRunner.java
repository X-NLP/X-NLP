package com.xnlp.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
                        () -> new ResourceDatabasePopulator(new ClassPathResource(BASELINE_RESOURCE)).execute(dataSource)),
                new MigrationDefinition(
                        2,
                        "evaluation-progress-columns",
                        checksum(2, "evaluation-progress-columns", "total_entries|processed_entries|progress_percent|cancel_requested"),
                        this::ensureEvaluationRunColumns),
                new MigrationDefinition(
                        3,
                        "waste-weighing-trip-number",
                        checksum(3, "waste-weighing-trip-number", "trip_no"),
                        this::ensureWasteWeighingColumns)
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
