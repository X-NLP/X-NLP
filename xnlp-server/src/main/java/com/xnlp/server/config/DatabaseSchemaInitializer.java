package com.xnlp.server.config;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Adds columns introduced by newer X-NLP releases to an existing database.
 *
 * <p>The bootstrap schema intentionally uses {@code CREATE TABLE IF NOT EXISTS}
 * so that fresh MySQL, PostgreSQL, and H2 databases can be selected through a
 * Spring profile. That statement does not upgrade an already-created table,
 * however, so this small metadata-driven compatibility pass keeps local and
 * upgraded installations usable without a vendor-specific migration tool.</p>
 */
@Component
@Profile("!memory")
public class DatabaseSchemaInitializer {

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public DatabaseSchemaInitializer(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        ensureEvaluationRunColumns();
        ensureWasteWeighingColumns();
    }

    private void ensureEvaluationRunColumns() {
        List<ColumnDefinition> columns = List.of(
                new ColumnDefinition("total_entries", "INTEGER NOT NULL DEFAULT 0"),
                new ColumnDefinition("processed_entries", "INTEGER NOT NULL DEFAULT 0"),
                new ColumnDefinition("progress_percent", "DOUBLE PRECISION NOT NULL DEFAULT 0"),
                new ColumnDefinition("cancel_requested", "BOOLEAN NOT NULL DEFAULT FALSE")
        );

        try (Connection connection = dataSource.getConnection()) {
            for (ColumnDefinition column : columns) {
                if (!hasColumn(connection.getMetaData(), connection, "evaluation_runs", column.name())) {
                    jdbc.execute("ALTER TABLE evaluation_runs ADD COLUMN " + column.name() + " " + column.definition());
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to validate or upgrade the X-NLP database schema", ex);
        }
    }

    private void ensureWasteWeighingColumns() {
        try (Connection connection = dataSource.getConnection()) {
            if (!hasColumn(connection.getMetaData(), connection, "waste_weighings", "trip_no")) {
                jdbc.execute("ALTER TABLE waste_weighings ADD COLUMN trip_no INTEGER NOT NULL DEFAULT 1");
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to validate or upgrade the waste weighing schema", ex);
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

    private record ColumnDefinition(String name, String definition) {
    }
}
