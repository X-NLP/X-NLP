package com.xnlp.server;

import com.xnlp.core.rag.KnowledgeVectorStore;
import com.xnlp.core.rag.VectorRecord;
import com.xnlp.server.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
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
                "spring.datasource.url=jdbc:h2:mem:xnlp-rag-chat-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=never",
                "spring.ai.model.chat=none",
                "spring.ai.model.embedding=none",
                "spring.ai.openai.api-key=test-key",
                "xnlp.rag.retrieval.max-retries=0"
        })
@ActiveProfiles("h2")
@Import(RagChatIntegrationTest.AiTestConfiguration.class)
@DisplayName("Grounded RAG chat HTTP API")
class RagChatIntegrationTest {

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
    void rag_returnsGroundedAnswerCanonicalCitationAndDiagnostics() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge-bases/kb-1/rag")
                        .contentType("application/json")
                        .content("""
                                {
                                  "message": "How can I switch databases?",
                                  "topK": 2,
                                  "maxContextChunks": 1,
                                  "conversationId": "conversation-1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Use the portable database guide [S1]."))
                .andExpect(jsonPath("$.lowConfidence").value(false))
                .andExpect(jsonPath("$.citations.length()").value(1))
                .andExpect(jsonPath("$.citations[0].documentId").value("doc-a"))
                .andExpect(jsonPath("$.citations[0].chunkId").value("chunk-a"))
                .andExpect(jsonPath("$.retrieval.matches.length()").value(2))
                .andExpect(jsonPath("$.model").isNotEmpty())
                .andExpect(jsonPath("$.provider").isNotEmpty())
                .andExpect(jsonPath("$.elapsedMs").isNumber());
    }

    @Test
    void rag_emptyRetrievalUsesStableInsufficientContextError() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge-bases/kb-1/rag")
                        .contentType("application/json")
                        .header("X-Request-ID", "req-rag-empty")
                        .content("""
                                {
                                  "message": "unmatched",
                                  "topK": 2,
                                  "minScore": 1.0
                                }
                                """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("insufficient_context"))
                .andExpect(jsonPath("$.message").value("No sufficient knowledge context was found"))
                .andExpect(jsonPath("$.requestId").value("req-rag-empty"));
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
                vector("chunk-b", "doc-b", new float[]{0.9f, 0.1f})));
    }

    private void insertDocument(String documentId, String title, String chunkId, String content) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO knowledge_documents
                    (tenant_id, id, knowledge_base_id, title, source_type, content_text,
                     content_checksum, version, index_status, metadata_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "default", documentId, "kb-1", title, "TEXT", content,
                "checksum-" + documentId, 1, "INDEXED", "{}", now, now);
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
    static class AiTestConfiguration {

        @Bean
        @Primary
        EmbeddingModel ragEmbeddingModel() {
            EmbeddingModel model = mock(EmbeddingModel.class);
            when(model.embed(anyString())).thenAnswer(invocation ->
                    "unmatched".equals(invocation.getArgument(0))
                            ? new float[]{0, 1}
                            : new float[]{1, 0});
            return model;
        }

        @Bean
        @Primary
        ChatModel ragChatModel() {
            return prompt -> new ChatResponse(List.of(
                    new Generation(new AssistantMessage("Use the portable database guide [S1]."))));
        }
    }
}
