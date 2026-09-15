package com.xnlp.server.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xnlp.core.rag.VectorRecord;
import com.xnlp.core.rag.VectorSearchRequest;
import com.xnlp.server.config.DatabaseMigrationProperties;
import com.xnlp.server.config.DatabaseMigrationRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcKnowledgeVectorStorePersistenceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void fileBackedH2_reopensPersistedVectorsAfterRestart() {
        String url = "jdbc:h2:file:" + tempDirectory.resolve("knowledge-store").toAbsolutePath()
                + ";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE";

        DataSource firstDataSource = dataSource(url);
        JdbcTemplate firstJdbc = new JdbcTemplate(firstDataSource);
        migrate(firstDataSource, firstJdbc);
        insertSource(firstJdbc);
        new JdbcKnowledgeVectorStore(firstJdbc, new ObjectMapper()).upsert("tenant-a", List.of(
                new VectorRecord(
                        "chunk-1", "kb-1", "doc-1", "embed-v1", 2, new float[]{1, 0},
                        "checksum-chunk-1", Map.of("persisted", true), Instant.now())));
        firstJdbc.execute("SHUTDOWN");

        DataSource restartedDataSource = dataSource(url);
        JdbcTemplate restartedJdbc = new JdbcTemplate(restartedDataSource);
        migrate(restartedDataSource, restartedJdbc);
        JdbcKnowledgeVectorStore restartedStore = new JdbcKnowledgeVectorStore(restartedJdbc, new ObjectMapper());

        var matches = restartedStore.search(new VectorSearchRequest(
                "tenant-a", "kb-1", "embed-v1", new float[]{1, 0}, 10, null, Map.of()));

        assertThat(matches).singleElement().satisfies(match -> {
            assertThat(match.chunkId()).isEqualTo("chunk-1");
            assertThat(match.content()).isEqualTo("persistent content");
            assertThat(match.metadata()).containsEntry("persisted", true);
        });
        restartedJdbc.execute("SHUTDOWN");
    }

    private static DataSource dataSource(String url) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static void migrate(DataSource dataSource, JdbcTemplate jdbc) {
        new DatabaseMigrationRunner(jdbc, dataSource, new DatabaseMigrationProperties());
    }

    private static void insertSource(JdbcTemplate jdbc) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO knowledge_bases
                    (tenant_id, id, name, embedding_model, chunk_max_characters,
                     chunk_overlap_characters, chunk_separator_mode, status,
                     document_count, chunk_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "tenant-a", "kb-1", "Persistent KB", "embed-v1", 1200, 200,
                "PARAGRAPH", "ACTIVE", 1, 1, now, now);
        jdbc.update("""
                INSERT INTO knowledge_documents
                    (tenant_id, id, knowledge_base_id, title, source_type, content_text,
                     content_checksum, version, index_status, metadata_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "tenant-a", "doc-1", "kb-1", "Persistent document", "TEXT",
                "persistent content", "checksum-doc-1", 1, "INDEXED", "{}", now, now);
        jdbc.update("""
                INSERT INTO knowledge_chunks
                    (tenant_id, id, knowledge_base_id, document_id, seq, content_text,
                     content_checksum, start_offset, end_offset, metadata_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "tenant-a", "chunk-1", "kb-1", "doc-1", 0, "persistent content",
                "checksum-chunk-1", 0, 18, "{}");
    }
}
