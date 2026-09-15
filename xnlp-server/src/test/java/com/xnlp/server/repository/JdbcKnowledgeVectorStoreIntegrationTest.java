package com.xnlp.server.repository;

import com.xnlp.core.rag.RagContractException;
import com.xnlp.core.rag.RagErrorCode;
import com.xnlp.core.rag.VectorRecord;
import com.xnlp.core.rag.VectorSearchRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:xnlp-vector-store-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=never",
                "spring.ai.model.chat=none",
                "spring.ai.openai.api-key=test-key"
        })
@ActiveProfiles("h2")
@DisplayName("Portable JDBC knowledge vector store")
class JdbcKnowledgeVectorStoreIntegrationTest {

    @Autowired
    private JdbcKnowledgeVectorStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearKnowledgeData() {
        jdbc.update("DELETE FROM knowledge_embeddings");
        jdbc.update("DELETE FROM knowledge_chunks");
        jdbc.update("DELETE FROM knowledge_documents");
        jdbc.update("DELETE FROM knowledge_bases");
    }

    @Test
    @DisplayName("performs deterministic cosine Top-K, metadata filtering and deletion")
    void searchAndDelete_usePortableJsonVectors() {
        insertSource("tenant-a", "kb-1", "doc-1", "Guide", "en",
                List.of(
                        chunk("chunk-1", 0, "alpha", "intro"),
                        chunk("chunk-2", 1, "beta", "details"),
                        chunk("chunk-3", 2, "gamma", "intro")));
        store.upsert("tenant-a", List.of(
                vector("chunk-1", "kb-1", "doc-1", new float[]{1, 0}, Map.of("quality", "approved")),
                vector("chunk-2", "kb-1", "doc-1", new float[]{0, 1}, Map.of()),
                vector("chunk-3", "kb-1", "doc-1", new float[]{0.7f, 0.7f}, Map.of())));

        var matches = store.search(new VectorSearchRequest(
                "tenant-a", "kb-1", "embed-v1", new float[]{1, 0}, 2, null, Map.of("section", "intro")));

        assertThat(matches).extracting(match -> match.chunkId()).containsExactly("chunk-1", "chunk-3");
        assertThat(matches.getFirst().score()).isEqualTo(1.0);
        assertThat(matches.getFirst().metadata())
                .containsEntry("language", "en")
                .containsEntry("section", "intro")
                .containsEntry("quality", "approved");

        var highConfidence = store.search(new VectorSearchRequest(
                "tenant-a", "kb-1", "embed-v1", new float[]{1, 0}, 10, 0.8, Map.of()));
        assertThat(highConfidence).extracting(match -> match.chunkId()).containsExactly("chunk-1");
        assertThat(store.count("tenant-a", "kb-1")).isEqualTo(3);

        store.upsert("tenant-a", List.of(
                new VectorRecord(
                        "chunk-1", "kb-1", "doc-1", "embed-v1", 2, new float[]{0, 1},
                        "checksum-revised", Map.of("quality", "revised"), Instant.now())));

        var replaced = store.search(new VectorSearchRequest(
                "tenant-a", "kb-1", "embed-v1", new float[]{0, 1}, 10, null,
                Map.of("quality", "revised")));
        assertThat(replaced).singleElement().satisfies(match -> {
            assertThat(match.chunkId()).isEqualTo("chunk-1");
            assertThat(match.score()).isEqualTo(1.0);
            assertThat(match.metadata()).containsEntry("quality", "revised");
        });
        assertThat(store.count("tenant-a", "kb-1")).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT content_checksum FROM knowledge_embeddings
                WHERE tenant_id = ? AND chunk_id = ? AND embedding_model = ?
                """, String.class, "tenant-a", "chunk-1", "embed-v1"))
                .isEqualTo("checksum-revised");

        store.deleteByDocument("tenant-a", "kb-1", "doc-1");
        assertThat(store.count("tenant-a", "kb-1")).isZero();
    }

    @Test
    @DisplayName("keeps identical knowledge ids isolated by explicit tenant")
    void search_isTenantScoped() {
        insertSource("tenant-a", "shared-kb", "shared-doc", "Tenant A", "en",
                List.of(chunk("shared-chunk", 0, "alpha", "a")));
        insertSource("tenant-b", "shared-kb", "shared-doc", "Tenant B", "zh",
                List.of(chunk("shared-chunk", 0, "beta", "b")));
        VectorRecord shared = vector("shared-chunk", "shared-kb", "shared-doc", new float[]{1, 0}, Map.of());
        store.upsert("tenant-a", List.of(shared));
        store.upsert("tenant-b", List.of(shared));

        var tenantA = store.search(request("tenant-a", "shared-kb"));
        var tenantB = store.search(request("tenant-b", "shared-kb"));

        assertThat(tenantA).singleElement().satisfies(match -> {
            assertThat(match.title()).isEqualTo("Tenant A");
            assertThat(match.content()).isEqualTo("alpha");
        });
        assertThat(tenantB).singleElement().satisfies(match -> {
            assertThat(match.title()).isEqualTo("Tenant B");
            assertThat(match.content()).isEqualTo("beta");
        });

        store.deleteByKnowledgeBase("tenant-a", "shared-kb");
        assertThat(store.count("tenant-a", "shared-kb")).isZero();
        assertThat(store.count("tenant-b", "shared-kb")).isEqualTo(1);
    }

    @Test
    @DisplayName("rejects mixed vector dimensions instead of truncating")
    void search_rejectsDimensionMismatch() {
        insertSource("tenant-a", "kb-1", "doc-1", "Guide", "en",
                List.of(chunk("chunk-1", 0, "alpha", "intro")));
        store.upsert("tenant-a", List.of(
                vector("chunk-1", "kb-1", "doc-1", new float[]{1, 0, 0}, Map.of())));

        assertThatThrownBy(() -> store.search(request("tenant-a", "kb-1")))
                .isInstanceOfSatisfying(RagContractException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(RagErrorCode.VECTOR_DIMENSION_MISMATCH));
    }

    @Test
    @DisplayName("rejects corrupted persisted embedding dimensions with a stable domain error")
    void search_rejectsCorruptedPersistedVector() {
        insertSource("tenant-a", "kb-1", "doc-1", "Guide", "en",
                List.of(chunk("chunk-1", 0, "alpha", "intro")));
        store.upsert("tenant-a", List.of(
                vector("chunk-1", "kb-1", "doc-1", new float[]{1, 0}, Map.of())));
        jdbc.update("""
                UPDATE knowledge_embeddings
                SET embedding_json = ?
                WHERE tenant_id = ? AND chunk_id = ? AND embedding_model = ?
                """, "[1.0]", "tenant-a", "chunk-1", "embed-v1");

        assertThatThrownBy(() -> store.search(request("tenant-a", "kb-1")))
                .isInstanceOfSatisfying(RagContractException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(RagErrorCode.VECTOR_DIMENSION_MISMATCH);
                    assertThat(error.getDetail())
                            .containsEntry("declared", 2)
                            .containsEntry("actual", 1)
                            .containsEntry("chunkId", "chunk-1");
                });
    }

    private VectorSearchRequest request(String tenantId, String knowledgeBaseId) {
        return new VectorSearchRequest(
                tenantId, knowledgeBaseId, "embed-v1", new float[]{1, 0}, 10, null, Map.of());
    }

    private VectorRecord vector(
            String chunkId,
            String knowledgeBaseId,
            String documentId,
            float[] embedding,
            Map<String, Object> metadata) {
        return new VectorRecord(
                chunkId, knowledgeBaseId, documentId, "embed-v1", embedding.length, embedding,
                "checksum-" + chunkId, metadata, Instant.now());
    }

    private ChunkFixture chunk(String id, int sequence, String content, String section) {
        return new ChunkFixture(id, sequence, content, section);
    }

    private void insertSource(
            String tenantId,
            String knowledgeBaseId,
            String documentId,
            String title,
            String language,
            List<ChunkFixture> chunks) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO knowledge_bases
                    (tenant_id, id, name, embedding_model, chunk_max_characters,
                     chunk_overlap_characters, chunk_separator_mode, status,
                     document_count, chunk_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, knowledgeBaseId, title, "embed-v1", 1200, 200, "PARAGRAPH", "ACTIVE",
                1, chunks.size(), now, now);
        jdbc.update("""
                INSERT INTO knowledge_documents
                    (tenant_id, id, knowledge_base_id, title, source_type, content_text,
                     content_checksum, version, index_status, metadata_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, documentId, knowledgeBaseId, title, "TEXT", "source-" + title,
                "checksum-" + documentId, 1, "INDEXED", "{\"language\":\"" + language + "\"}", now, now);
        for (ChunkFixture chunk : chunks) {
            jdbc.update("""
                    INSERT INTO knowledge_chunks
                        (tenant_id, id, knowledge_base_id, document_id, seq, content_text,
                         content_checksum, start_offset, end_offset, metadata_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, tenantId, chunk.id(), knowledgeBaseId, documentId, chunk.sequence(), chunk.content(),
                    "checksum-" + chunk.id(), 0, chunk.content().length(),
                    "{\"section\":\"" + chunk.section() + "\"}");
        }
    }

    private record ChunkFixture(String id, int sequence, String content, String section) {
    }
}
