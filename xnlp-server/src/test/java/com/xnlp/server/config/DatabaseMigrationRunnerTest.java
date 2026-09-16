package com.xnlp.server.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseMigrationRunnerTest {

    private static final String BASELINE_FRAGMENT = """
            elapsed_seconds DOUBLE PRECISION,
            estimated_weight_tons DOUBLE NOT NULL,
            remaining_weight_tons DOUBLE NOT NULL DEFAULT 0
            """;

    @Test
    void renderBaselineSql_Postgres_UsesDoublePrecision() {
        String rendered = DatabaseMigrationRunner.renderBaselineSql(BASELINE_FRAGMENT, "postgresql");

        assertThat(rendered)
                .contains("elapsed_seconds DOUBLE PRECISION")
                .contains("estimated_weight_tons DOUBLE PRECISION NOT NULL")
                .contains("remaining_weight_tons DOUBLE PRECISION NOT NULL DEFAULT 0")
                .doesNotContainPattern("\\bDOUBLE\\s+NOT NULL");
    }

    @Test
    void renderBaselineSql_Mysql_PreservesDoubleType() {
        assertThat(DatabaseMigrationRunner.renderBaselineSql(BASELINE_FRAGMENT, "mysql"))
                .isEqualTo(BASELINE_FRAGMENT);
    }

    @Test
    void renderBaselineSql_H2_PreservesPortableSource() {
        assertThat(DatabaseMigrationRunner.renderBaselineSql(BASELINE_FRAGMENT, "h2"))
                .isEqualTo(BASELINE_FRAGMENT);
    }
}
