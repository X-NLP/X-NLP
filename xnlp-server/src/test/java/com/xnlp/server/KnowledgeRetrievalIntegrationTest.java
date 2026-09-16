package com.xnlp.server;

import com.xnlp.core.rag.KnowledgeVectorStore;
import com.xnlp.core.rag.VectorRecord;
import com.xnlp.server.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:xnlp-retrieval-api-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=never",
                "spring.ai.model.chat=none",
                "spring.ai.model.embedding=none",
                "spring.ai.openai.api-key=test-key",
                "xnlp.rag.retrieval.max-retries=0"
        })
@ActiveProfiles("h2")
@Import(KnowledgeRetrievalIntegrationTest.EmbeddingTestConfiguration.class)
@DisplayName("Knowledge retrieval HTTP API")
class KnowledgeRetrievalIntegrationTest {

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KnowledgeVectorStore vectorStore;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        jdbc.update("DELETE FROM ingestion_jobs");
        jdbc.update("DELETE FROM knowledge_embeddings");
        jdbc.update("DELETE FROM knowledge_chunks");
        jdbc.update("DELETE FROM knowledge_documents");
        jdbc.update("DELETE FROM knowledge_bases");
        TenantContext.clear();
        insertKnowledgeData();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void search_returnsPersistentMatchesAndFallsBackWhenRerankerIsUnconfigured() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge-bases/kb-1/search")
                        .contentType("application/json")
                        .content("""
                                {
                                  "query": "portable databases",
                                  "topK": 2,
                                  "filter": {"language": "en"},
                                  "rerank": true,
                                  "rerankTopN": 2
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("portable databases"))
                .andExpect(jsonPath("$.embeddingModel").value("embed-v1"))
                .andExpect(jsonPath("$.reranker").doesNotExist())
                .andExpect(jsonPath("$.matches.length()").value(2))
                .andExpect(jsonPath("$.matches[0].documentId").value("doc-a"))
                .andExpect(jsonPath("$.matches[0].chunkId").value("chunk-a"))
                .andExpect(jsonPath("$.matches[1].documentId").value("doc-b"))
                .andExpect(jsonPath("$.elapsedMs").isNumber());
    }

    @Test
    void search_missingKnowledgeBaseUsesStableErrorContract() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge-bases/missing/search")
                        .contentType("application/json")
                        .header("X-Request-ID", "req-retrieval")
                        .content("{\"query\":\"hello\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("knowledge_base_not_found"))
                .andExpect(jsonPath("$.message").value("Knowledge base was not found"))
                .andExpect(jsonPath("$.requestId").value("req-retrieval"));
    }

    private void insertKnowledgeData() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO knowledge_bases
                    (tenant_id, id, name, embedding_model, chunk_max_characters,
                     chunk_overlap_characters, chunk_separator_mode, status,
                     document_count, chunk_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "default", "kb-1", "Handbook", "embed-v1", 1200, 200,
                "PARAGRAPH", "ACTIVE", 2, 2, now, now);
        insertDocument("doc-a", "A guide", "chunk-a", "portable database guide");
        insertDocument("doc-b", "B guide", "chunk-b", "portable storage guide");
        vectorStore.upsert("default", List.of(
                vector("chunk-a", "doc-a", new float[]{1, 0}),
                vector("chunk-b", "doc-b", new float[]{1, 0})));
    }

    private void insertDocument(String documentId, String title, String chunkId, String content) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO knowledge_documents
                    (tenant_id, id, knowledge_base_id, title, source_type, content_text,
                     content_checksum, version, index_status, metadata_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "default", documentId, "kb-1", title, "TEXT", content,
                "checksum-" + documentId, 1, "INDEXED", "{\"language\":\"en\"}", now, now);
        jdbc.update("""
                INSERT INTO knowledge_chunks
                    (tenant_id, id, knowledge_base_id, document_id, seq, content_text,
                     content_checksum, start_offset, end_offset, metadata_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "default", chunkId, "kb-1", documentId, 0, content,
                "checksum-" + chunkId, 0, content.length(), "{}");
    }

    private VectorRecord vector(String chunkId, String documentId, float[] embedding) {
        return new VectorRecord(
                chunkId, "kb-1", documentId, "embed-v1", embedding.length, embedding,
                "checksum-" + chunkId, Map.of(), Instant.now());
    }

    @TestConfiguration
    static class EmbeddingTestConfiguration {

        @Bean
        @Primary
        EmbeddingModel retrievalEmbeddingModel() {
            EmbeddingModel model = mock(EmbeddingModel.class);
            when(model.embed(anyString())).thenReturn(new float[]{1, 0});
            return model;
        }
    }
}
