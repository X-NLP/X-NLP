package com.xnlp.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:xnlp-migration-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=never",
                "spring.ai.model.chat=none",
                "spring.ai.openai.api-key=test-key"
        })
@ActiveProfiles("h2")
@DisplayName("X-NLP database migrations")
class DatabaseMigrationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("applies ordered migrations and records checksums")
    void appliesVersionedMigrations() {
        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT version, description, checksum FROM xnlp_schema_history ORDER BY version");

        assertThat(history).hasSize(3);
        assertThat(history).extracting(row -> row.get("VERSION")).containsExactly(1, 2, 3);
        assertThat(history).extracting(row -> row.get("DESCRIPTION"))
                .containsExactly("baseline", "evaluation-progress-columns", "waste-weighing-trip-number");
        assertThat(history).allSatisfy(row -> assertThat(row.get("CHECKSUM")).isNotNull());

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM evaluation_runs WHERE 1 = 0", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM waste_weighings WHERE 1 = 0", Integer.class)).isZero();
    }
}
