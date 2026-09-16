package com.xnlp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xnlp.core.eval.EvaluationDataset;
import com.xnlp.core.eval.EvaluationEntry;
import com.xnlp.core.eval.NLPTaskType;
import com.xnlp.core.rag.KnowledgeVectorStore;
import com.xnlp.core.rag.RagContractException;
import com.xnlp.core.rag.RagErrorCode;
import com.xnlp.core.rag.RetrievalMatch;
import com.xnlp.core.rag.RetrievalReranker;
import com.xnlp.core.rag.VectorRecord;
import com.xnlp.core.rag.eval.RetrievalEvaluationRun;
import com.xnlp.core.repository.RetrievalEvaluationRepository;
import com.xnlp.server.service.DatasetService;
import com.xnlp.server.service.KnowledgeIngestionService;
import com.xnlp.server.service.RetrievalEvaluationService;
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
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:xnlp-retrieval-evaluation-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=never",
                "spring.ai.model.chat=none",
                "spring.ai.model.embedding=none",
                "spring.ai.openai.api-key=test-key",
                "xnlp.rag.retrieval.max-retries=0"
        })
@ActiveProfiles("h2")
@Import(RetrievalEvaluationIntegrationTest.ProviderConfiguration.class)
@DisplayName("Retrieval evaluation HTTP API")
class RetrievalEvaluationIntegrationTest {

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KnowledgeVectorStore vectorStore;

    @Autowired
    private RetrievalEvaluationService evaluationService;

    @Autowired
    private RetrievalEvaluationRepository evaluationRepository;

    @Autowired
    private KnowledgeIngestionService ingestionService;

    @Autowired
    private DatasetService datasetService;

    @Autowired
    private ObjectMapper mapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        jdbc.update("DELETE FROM retrieval_evaluation_samples");
        jdbc.update("DELETE FROM retrieval_evaluation_runs");
        jdbc.update("DELETE FROM dataset_entries");
        jdbc.update("DELETE FROM datasets");
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
    void create_persistsMacroMetricsMissesScoresAndRerankChanges() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/knowledge-bases/kb-1/retrieval-evaluations")
                        .contentType("application/json")
                        .content("""
                                {
                                  "samples": [
                                    {"id":"hit","query":"portable database","relevantChunkIds":["chunk-b"]},
                                    {"id":"miss","query":"unknown","relevantChunkIds":["missing-chunk"]}
                                  ],
                                  "topK": 2,
                                  "rerank": true,
                                  "rerankTopN": 2
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.knowledgeBaseId").value("kb-1"))
                .andExpect(jsonPath("$.totalSamples").value(2))
                .andReturn();
        String runId = mapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        RetrievalEvaluationRun completed = awaitTerminal(runId);
        org.assertj.core.api.Assertions.assertThat(completed.status())
                .isEqualTo(RetrievalEvaluationRun.Status.COMPLETED);
        org.assertj.core.api.Assertions.assertThat(completed.processedSamples()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(completed.metrics().recallAtK()).isEqualTo(0.5);
        org.assertj.core.api.Assertions.assertThat(completed.metrics().mrr()).isEqualTo(0.5);
        org.assertj.core.api.Assertions.assertThat(completed.metrics().ndcgAtK()).isEqualTo(0.5);
        org.assertj.core.api.Assertions.assertThat(completed.metrics().averageLatencyMs()).isGreaterThanOrEqualTo(0);

        mockMvc.perform(get("/api/v1/knowledge-bases/kb-1/retrieval-evaluations/{runId}/samples", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sampleId").value("hit"))
                .andExpect(jsonPath("$[0].miss").value(false))
                .andExpect(jsonPath("$[0].rerankChanged").value(true))
                .andExpect(jsonPath("$[0].rawMatches[0].chunkId").value("chunk-a"))
                .andExpect(jsonPath("$[0].finalMatches[0].chunkId").value("chunk-b"))
                .andExpect(jsonPath("$[0].finalMatches[0].rerankScore").isNumber())
                .andExpect(jsonPath("$[1].sampleId").value("miss"))
                .andExpect(jsonPath("$[1].miss").value(true))
                .andExpect(jsonPath("$[1].recallAtK").value(0));
    }

    @Test
    void create_acceptsDatasetEntriesWithRelevantChunkLabels() throws Exception {
        EvaluationEntry entry = new EvaluationEntry(null, "portable database", null);
        entry.setLabels(Map.of("relevantChunkIds", List.of("chunk-a")));
        EvaluationDataset dataset = new EvaluationDataset();
        dataset.setName("Retrieval dataset");
        dataset.setDescription("Dataset-backed retrieval evaluation");
        dataset.setTaskType(NLPTaskType.QUESTION_ANSWERING);
        dataset.setEntries(List.of(entry));
        EvaluationDataset createdDataset = datasetService.create(dataset);

        MvcResult created = mockMvc.perform(post("/api/v1/knowledge-bases/kb-1/retrieval-evaluations")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of(
                                "datasetId", createdDataset.getId(),
                                "topK", 2))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.datasetId").value(createdDataset.getId()))
                .andExpect(jsonPath("$.totalSamples").value(1))
                .andReturn();
        String runId = mapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        RetrievalEvaluationRun completed = awaitTerminal(runId);
        org.assertj.core.api.Assertions.assertThat(completed.status())
                .isEqualTo(RetrievalEvaluationRun.Status.COMPLETED);
        org.assertj.core.api.Assertions.assertThat(evaluationService.samples("kb-1", runId))
                .singleElement()
                .satisfies(result -> {
                    org.assertj.core.api.Assertions.assertThat(result.query()).isEqualTo("portable database");
                    org.assertj.core.api.Assertions.assertThat(result.relevantChunkIds()).containsExactly("chunk-a");
                });
    }

    @Test
    void create_rejectsMissingOrAmbiguousSampleSource() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge-bases/kb-1/retrieval-evaluations")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_error"));

        mockMvc.perform(post("/api/v1/knowledge-bases/kb-1/retrieval-evaluations")
                        .contentType("application/json")
                        .content("""
                                {
                                  "datasetId": "dataset-1",
                                  "samples": [{"query":"q","relevantChunkIds":["chunk-a"]}]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }


    @Test
    void deleteKnowledgeBase_rejectsActiveEvaluationThenCleansTerminalEvidence() {
        Instant createdAt = Instant.now();
        RetrievalEvaluationRun active = new RetrievalEvaluationRun(
                "run-delete", "kb-1", null, RetrievalEvaluationRun.Status.RUNNING,
                2, null, Map.of(), false, 2, 1, 0, null, null, createdAt, null);
        evaluationRepository.saveRun("default", active);

        assertThatThrownBy(() -> ingestionService.deleteKnowledgeBase("kb-1", true))
                .isInstanceOfSatisfying(RagContractException.class, error ->
                        assertThat(error.getErrorCode())
                                .isEqualTo(RagErrorCode.RETRIEVAL_EVALUATION_IN_PROGRESS));
        assertThat(evaluationRepository.hasActiveRun("default", "kb-1")).isTrue();
        assertThat(evaluationRepository.findRun("tenant-b", "kb-1", active.id())).isEmpty();

        RetrievalEvaluationRun terminal = new RetrievalEvaluationRun(
                active.id(), active.knowledgeBaseId(), null, RetrievalEvaluationRun.Status.FAILED,
                active.topK(), active.minScore(), active.filter(), active.rerank(), active.rerankTopN(),
                active.totalSamples(), 0, null, "test terminal", createdAt, Instant.now());
        evaluationRepository.saveRun("default", terminal);
        ingestionService.deleteKnowledgeBase("kb-1", true);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM retrieval_evaluation_runs WHERE knowledge_base_id = 'kb-1'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_bases WHERE id = 'kb-1'", Integer.class)).isZero();
    }

    private RetrievalEvaluationRun awaitTerminal(String runId) throws InterruptedException {
        RetrievalEvaluationRun latest = null;
        for (int attempt = 0; attempt < 200; attempt++) {
            latest = evaluationService.get("kb-1", runId);
            if (latest.status() == RetrievalEvaluationRun.Status.COMPLETED
                    || latest.status() == RetrievalEvaluationRun.Status.FAILED) {
                return latest;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Evaluation did not complete: " + latest);
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
                vector("chunk-a", "doc-a"), vector("chunk-b", "doc-b")));
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

    private VectorRecord vector(String chunkId, String documentId) {
        return new VectorRecord(
                chunkId, "kb-1", documentId, "embed-v1", 2, new float[]{1, 0},
                "checksum-" + chunkId, Map.of(), Instant.now());
    }

    @TestConfiguration
    static class ProviderConfiguration {

        @Bean
        @Primary
        EmbeddingModel retrievalEvaluationEmbeddingModel() {
            EmbeddingModel model = mock(EmbeddingModel.class);
            when(model.embed(anyString())).thenReturn(new float[]{1, 0});
            return model;
        }

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        RetrievalReranker retrievalEvaluationReranker() {
            return new RetrievalReranker() {
                @Override
                public String name() {
                    return "deterministic-test-reranker";
                }

                @Override
                public List<RetrievalMatch> rerank(
                        String query, List<RetrievalMatch> candidates, int topN) {
                    List<RetrievalMatch> result = new ArrayList<>();
                    for (int index = candidates.size() - 1; index >= 0 && result.size() < topN; index--) {
                        RetrievalMatch candidate = candidates.get(index);
                        result.add(new RetrievalMatch(
                                candidate.documentId(), candidate.chunkId(), candidate.title(), candidate.content(),
                                candidate.sourceUri(), candidate.score(), 0.95 - result.size() * 0.1,
                                candidate.metadata()));
                    }
                    return result;
                }
            };
        }
    }
}
