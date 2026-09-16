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

        assertThat(history).hasSize(10);
        assertThat(history).extracting(row -> row.get("VERSION")).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(history).extracting(row -> row.get("DESCRIPTION"))
                .containsExactly("baseline", "evaluation-progress-columns", "waste-weighing-trip-number",
                        "multi-tenant-isolation", "rag-storage", "ingestion-control-and-rag-constraints",
                        "retrieval-evaluation", "identity-rbac", "api-key-audit", "tenant-quota");
        assertThat(history).allSatisfy(row -> assertThat(row.get("CHECKSUM")).isNotNull());

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM evaluation_runs WHERE 1 = 0", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM waste_weighings WHERE 1 = 0", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM datasets WHERE tenant_id = 'default'", Integer.class)).isGreaterThanOrEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM waste_vehicles WHERE tenant_id = 'default'", Integer.class)).isGreaterThanOrEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_bases WHERE tenant_id = 'default'", Integer.class)).isGreaterThanOrEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_embeddings WHERE tenant_id = 'default'", Integer.class)).isGreaterThanOrEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ingestion_jobs WHERE cancel_requested = FALSE", Integer.class)).isGreaterThanOrEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM retrieval_evaluation_runs WHERE tenant_id = 'default'", Integer.class))
                .isGreaterThanOrEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM retrieval_evaluation_samples WHERE tenant_id = 'default'", Integer.class))
                .isGreaterThanOrEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenants WHERE id = 'default'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenant_memberships WHERE tenant_id = 'default'", Integer.class))
                .isGreaterThanOrEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM api_keys WHERE tenant_id = 'default'", Integer.class))
                .isGreaterThanOrEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE tenant_id = 'default'", Integer.class))
                .isGreaterThanOrEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenant_quotas WHERE tenant_id = 'default'", Integer.class))
                .isGreaterThanOrEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM quota_usage_windows WHERE tenant_id = 'default'", Integer.class))
                .isGreaterThanOrEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM quota_concurrency_leases WHERE tenant_id = 'default'", Integer.class))
                .isGreaterThanOrEqualTo(0);
    }
}
