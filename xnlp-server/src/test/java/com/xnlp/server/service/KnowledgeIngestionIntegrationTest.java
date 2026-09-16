package com.xnlp.server.service;

import com.xnlp.core.rag.ChunkPolicy;
import com.xnlp.core.rag.IngestionJob;
import com.xnlp.core.rag.KnowledgeDocument;
import com.xnlp.server.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:xnlp-ingestion-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=never",
                "spring.ai.model.chat=none",
                "spring.ai.model.embedding=none",
                "spring.ai.openai.api-key=test-key",
                "xnlp.rag.ingestion.batch-size=8",
                "xnlp.rag.ingestion.max-retries=1",
                "xnlp.rag.ingestion.retry-backoff=0ms",
                "xnlp.rag.ingestion.core-pool-size=1",
                "xnlp.rag.ingestion.max-pool-size=1",
                "xnlp.rag.ingestion.queue-capacity=20"
        })
@ActiveProfiles("h2")
@Import(KnowledgeIngestionIntegrationTest.EmbeddingTestConfiguration.class)
@DisplayName("Incremental knowledge document ingestion")
class KnowledgeIngestionIntegrationTest {

    @Autowired
    private KnowledgeIngestionService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EmbeddingControl embeddings;

    @BeforeEach
    void clearKnowledgeData() {
        embeddings.reset();
        jdbc.update("DELETE FROM ingestion_jobs");
        jdbc.update("DELETE FROM knowledge_embeddings");
        jdbc.update("DELETE FROM knowledge_chunks");
        jdbc.update("DELETE FROM knowledge_documents");
        jdbc.update("DELETE FROM knowledge_bases");
        TenantContext.clear();
    }

    @AfterEach
    void clearTenant() {
        embeddings.releaseBlockedCall();
        TenantContext.clear();
    }

    @Test
    void createDocument_sameExternalIdAndChecksum_isIdempotentAndTenantExplicit() {
        String tenant = "tenant-a";
        var knowledgeBase = TenantContext.callWithTenant(tenant, () -> service.createKnowledgeBase(
                "Handbook", null, "embed-test", new ChunkPolicy(100, 10, ChunkPolicy.SeparatorMode.FIXED)));
        var first = TenantContext.callWithTenant(tenant, () -> service.createDocument(
                knowledgeBase.id(), "Guide", longContent("stable"), KnowledgeDocument.SourceType.TEXT,
                null, "guide-1", Map.of("language", "en")));
        awaitJob(tenant, first.job().id(), IngestionJob.Status.COMPLETED);
        int callsAfterFirstImport = embeddings.calls();

        var duplicate = TenantContext.callWithTenant(tenant, () -> service.createDocument(
                knowledgeBase.id(), "Guide again", longContent("stable"), KnowledgeDocument.SourceType.TEXT,
                null, "guide-1", Map.of("language", "en")));

        assertThat(duplicate.unchanged()).isTrue();
        assertThat(duplicate.job()).isNull();
        assertThat(duplicate.document().id()).isEqualTo(first.document().id());
        assertThat(embeddings.calls()).isEqualTo(callsAfterFirstImport);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_documents WHERE tenant_id = ?", Integer.class, tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_embeddings WHERE tenant_id = ?", Integer.class, tenant)).isPositive();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_documents WHERE tenant_id = 'default'", Integer.class)).isZero();
    }

    @Test
    void updateDocument_changedContent_replacesOnlyThatDocumentsChunksAndDeleteCascades() {
        var knowledgeBase = service.createKnowledgeBase(
                "Operations", null, "embed-test", new ChunkPolicy(100, 20, ChunkPolicy.SeparatorMode.FIXED));
        var created = service.createDocument(
                knowledgeBase.id(), "Runbook", longContent("old"), KnowledgeDocument.SourceType.TEXT,
                null, "runbook-1", Map.of());
        awaitJob("default", created.job().id(), IngestionJob.Status.COMPLETED);
        List<String> oldChunkIds = jdbc.queryForList(
                "SELECT id FROM knowledge_chunks WHERE document_id = ? ORDER BY seq",
                String.class, created.document().id());

        var updated = service.updateDocument(
                knowledgeBase.id(), created.document().id(), null, longContent("new"), null, 1);
        awaitJob("default", updated.job().id(), IngestionJob.Status.COMPLETED);
        List<String> newChunkIds = jdbc.queryForList(
                "SELECT id FROM knowledge_chunks WHERE document_id = ? ORDER BY seq",
                String.class, created.document().id());

        assertThat(newChunkIds).isNotEmpty().isNotEqualTo(oldChunkIds);
        List<String> removedChunkIds = oldChunkIds.stream().filter(id -> !newChunkIds.contains(id)).toList();
        assertThat(removedChunkIds).isNotEmpty();
        for (String removedChunkId : removedChunkIds) {
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM knowledge_embeddings WHERE chunk_id = ?",
                    Integer.class, removedChunkId)).isZero();
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_embeddings WHERE chunk_id IN (SELECT id FROM knowledge_chunks WHERE document_id = ?)",
                Integer.class, created.document().id())).isEqualTo(newChunkIds.size());
        assertThat(service.getDocument(knowledgeBase.id(), created.document().id()))
                .satisfies(document -> {
                    assertThat(document.version()).isEqualTo(2);
                    assertThat(document.indexStatus()).isEqualTo(KnowledgeDocument.IndexStatus.INDEXED);
                });

        service.deleteDocument(knowledgeBase.id(), created.document().id());

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_documents WHERE id = ?", Integer.class, created.document().id())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_chunks WHERE document_id = ?", Integer.class, created.document().id())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_embeddings WHERE document_id = ?", Integer.class, created.document().id())).isZero();
        assertThat(service.getKnowledgeBase(knowledgeBase.id()))
                .satisfies(current -> {
                    assertThat(current.documentCount()).isZero();
                    assertThat(current.chunkCount()).isZero();
                });
    }

    @Test
    void embeddingTransientFailure_retriesAndCompletes() {
        embeddings.failNextCalls(1);
        var knowledgeBase = service.createKnowledgeBase(
                "Retry", null, "embed-test", new ChunkPolicy(500, 0, ChunkPolicy.SeparatorMode.FIXED));

        var submission = service.createDocument(
                knowledgeBase.id(), "Retry guide", longContent("retry"), KnowledgeDocument.SourceType.TEXT,
                null, "retry-1", Map.of());
        IngestionJob completed = awaitJob("default", submission.job().id(), IngestionJob.Status.COMPLETED);

        assertThat(completed.failedDocuments()).isZero();
        assertThat(embeddings.calls()).isEqualTo(2);
        assertThat(service.getDocument(knowledgeBase.id(), submission.document().id()).indexStatus())
                .isEqualTo(KnowledgeDocument.IndexStatus.INDEXED);
    }

    @Test
    void reindex_oneDocumentFails_marksJobPartialAndPreservesOtherDocument() {
        var knowledgeBase = service.createKnowledgeBase(
                "Partial", null, "embed-test", new ChunkPolicy(500, 0, ChunkPolicy.SeparatorMode.FIXED));
        var healthy = service.createDocument(
                knowledgeBase.id(), "Healthy", longContent("healthy"), KnowledgeDocument.SourceType.TEXT,
                null, "healthy-1", Map.of());
        awaitJob("default", healthy.job().id(), IngestionJob.Status.COMPLETED);
        var broken = service.createDocument(
                knowledgeBase.id(), "Broken", longContent("BROKEN"), KnowledgeDocument.SourceType.TEXT,
                null, "broken-1", Map.of());
        awaitJob("default", broken.job().id(), IngestionJob.Status.COMPLETED);
        embeddings.failWhenTextContains("BROKEN");

        IngestionJob reindex = service.startReindex(knowledgeBase.id(), List.of(), true);
        IngestionJob partial = awaitJob("default", reindex.id(), IngestionJob.Status.PARTIAL);

        assertThat(partial.processedDocuments()).isEqualTo(2);
        assertThat(partial.failedDocuments()).isEqualTo(1);
        assertThat(service.getDocument(knowledgeBase.id(), healthy.document().id()).indexStatus())
                .isEqualTo(KnowledgeDocument.IndexStatus.INDEXED);
        assertThat(service.getDocument(knowledgeBase.id(), broken.document().id()).indexStatus())
                .isEqualTo(KnowledgeDocument.IndexStatus.FAILED);
        assertThat(service.getKnowledgeBase(knowledgeBase.id()).status())
                .isEqualTo(com.xnlp.core.rag.KnowledgeBase.Status.ERROR);
    }

    @Test
    void cancelDuringEmbedding_stopsBeforeReplacingExistingIndex() throws Exception {
        var knowledgeBase = service.createKnowledgeBase(
                "Cancel", null, "embed-test", new ChunkPolicy(500, 0, ChunkPolicy.SeparatorMode.FIXED));
        var document = service.createDocument(
                knowledgeBase.id(), "Cancelable", longContent("cancel"), KnowledgeDocument.SourceType.TEXT,
                null, "cancel-1", Map.of());
        awaitJob("default", document.job().id(), IngestionJob.Status.COMPLETED);
        List<String> originalChunkIds = jdbc.queryForList(
                "SELECT id FROM knowledge_chunks WHERE document_id = ? ORDER BY seq",
                String.class, document.document().id());
        embeddings.blockNextCall();

        IngestionJob reindex = service.startReindex(knowledgeBase.id(), List.of(document.document().id()), true);
        assertThat(embeddings.awaitBlockedCall()).isTrue();
        service.cancelJob(reindex.id());
        embeddings.releaseBlockedCall();
        IngestionJob cancelled = awaitJob("default", reindex.id(), IngestionJob.Status.CANCELLED);

        assertThat(cancelled.processedDocuments()).isZero();
        assertThat(jdbc.queryForList(
                "SELECT id FROM knowledge_chunks WHERE document_id = ? ORDER BY seq",
                String.class, document.document().id())).containsExactlyElementsOf(originalChunkIds);
        assertThat(service.getDocument(knowledgeBase.id(), document.document().id()).indexStatus())
                .isEqualTo(KnowledgeDocument.IndexStatus.PENDING);
    }

    @Test
    void updateMetadataDuringEmbedding_requeuesLatestVersionWithoutRevivingStaleIndex() throws Exception {
        var knowledgeBase = service.createKnowledgeBase(
                "Concurrent updates", null, "embed-test",
                new ChunkPolicy(100, 10, ChunkPolicy.SeparatorMode.FIXED));
        embeddings.blockNextCall();
        var created = service.createDocument(
                knowledgeBase.id(), "Original title", longContent("concurrent"),
                KnowledgeDocument.SourceType.TEXT, null, "concurrent-1", Map.of("revision", 1));
        assertThat(embeddings.awaitBlockedCall()).isTrue();

        var updated = service.updateDocument(
                knowledgeBase.id(), created.document().id(), "Updated title", null,
                Map.of("revision", 2), created.document().version());
        embeddings.releaseBlockedCall();

        awaitJob("default", created.job().id(), IngestionJob.Status.FAILED);
        awaitJob("default", updated.job().id(), IngestionJob.Status.COMPLETED);
        KnowledgeDocument latest = service.getDocument(knowledgeBase.id(), created.document().id());
        assertThat(latest.version()).isEqualTo(2);
        assertThat(latest.title()).isEqualTo("Updated title");
        assertThat(latest.metadata()).containsEntry("revision", 2);
        assertThat(latest.indexStatus()).isEqualTo(KnowledgeDocument.IndexStatus.INDEXED);
    }

    @Test
    void updateMetadataDuringKnowledgeBaseReindex_restoresActiveStatusAfterLatestVersionCompletes() throws Exception {
        var knowledgeBase = service.createKnowledgeBase(
                "Concurrent reindex", null, "embed-test",
                new ChunkPolicy(100, 10, ChunkPolicy.SeparatorMode.FIXED));
        var created = service.createDocument(
                knowledgeBase.id(), "Original title", longContent("reindex-race"),
                KnowledgeDocument.SourceType.TEXT, null, "reindex-race-1", Map.of("revision", 1));
        awaitJob("default", created.job().id(), IngestionJob.Status.COMPLETED);
        embeddings.blockNextCall();

        IngestionJob reindex = service.startReindex(
                knowledgeBase.id(), List.of(created.document().id()), true);
        assertThat(embeddings.awaitBlockedCall()).isTrue();
        KnowledgeDocument indexing = service.getDocument(knowledgeBase.id(), created.document().id());
        var updated = service.updateDocument(
                knowledgeBase.id(), created.document().id(), "Updated title", null,
                Map.of("revision", 2), indexing.version());
        embeddings.releaseBlockedCall();

        awaitJob("default", reindex.id(), IngestionJob.Status.FAILED);
        awaitJob("default", updated.job().id(), IngestionJob.Status.COMPLETED);
        assertThat(service.getKnowledgeBase(knowledgeBase.id()).status())
                .isEqualTo(com.xnlp.core.rag.KnowledgeBase.Status.ACTIVE);
        assertThat(service.getDocument(knowledgeBase.id(), created.document().id()))
                .satisfies(latest -> {
                    assertThat(latest.version()).isEqualTo(2);
                    assertThat(latest.title()).isEqualTo("Updated title");
                    assertThat(latest.metadata()).containsEntry("revision", 2);
                    assertThat(latest.indexStatus()).isEqualTo(KnowledgeDocument.IndexStatus.INDEXED);
                });
    }

    private IngestionJob awaitJob(String tenantId, String jobId, IngestionJob.Status expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        IngestionJob last = null;
        while (System.nanoTime() < deadline) {
            last = TenantContext.callWithTenant(tenantId, () -> service.getJob(jobId));
            if (last.status() == expected) {
                return last;
            }
            if (last.status() == IngestionJob.Status.FAILED
                    || last.status() == IngestionJob.Status.PARTIAL
                    || last.status() == IngestionJob.Status.CANCELLED) {
                break;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for ingestion job", interrupted);
            }
        }
        throw new AssertionError("Expected job " + jobId + " to reach " + expected + " but was " + last);
    }

    private String longContent(String marker) {
        return (marker + " knowledge content with deterministic boundaries. ").repeat(8);
    }

    @TestConfiguration
    static class EmbeddingTestConfiguration {

        @Bean
        EmbeddingControl embeddingControl() {
            return new EmbeddingControl();
        }

        @Bean
        @Primary
        EmbeddingModel testEmbeddingModel(EmbeddingControl control) {
            EmbeddingModel model = mock(EmbeddingModel.class);
            when(model.embed(ArgumentMatchers.<String>anyList()))
                    .thenAnswer(invocation -> control.embed(invocation.getArgument(0)));
            return model;
        }
    }

    static class EmbeddingControl {

        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger failuresRemaining = new AtomicInteger();
        private final AtomicReference<String> failingMarker = new AtomicReference<>();
        private volatile CountDownLatch blockedCallStarted = new CountDownLatch(0);
        private volatile CountDownLatch blockedCallRelease = new CountDownLatch(0);
        private volatile boolean blockNext;

        List<float[]> embed(List<String> texts) throws Exception {
            calls.incrementAndGet();
            if (blockNext) {
                blockNext = false;
                blockedCallStarted.countDown();
                if (!blockedCallRelease.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test embedding call was not released");
                }
            }
            if (failuresRemaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw new IllegalStateException("transient provider failure");
            }
            String marker = failingMarker.get();
            if (marker != null && texts.stream().anyMatch(text -> text.contains(marker))) {
                throw new IllegalStateException("configured provider failure");
            }
            return texts.stream()
                    .map(text -> new float[]{text.length(), 1.0f + Math.floorMod(text.hashCode(), 97)})
                    .toList();
        }

        void reset() {
            calls.set(0);
            failuresRemaining.set(0);
            failingMarker.set(null);
            blockNext = false;
            blockedCallStarted = new CountDownLatch(0);
            blockedCallRelease = new CountDownLatch(0);
        }

        int calls() {
            return calls.get();
        }

        void failNextCalls(int count) {
            failuresRemaining.set(count);
        }

        void failWhenTextContains(String marker) {
            failingMarker.set(marker);
        }

        void blockNextCall() {
            blockedCallStarted = new CountDownLatch(1);
            blockedCallRelease = new CountDownLatch(1);
            blockNext = true;
        }

        boolean awaitBlockedCall() throws InterruptedException {
            return blockedCallStarted.await(5, TimeUnit.SECONDS);
        }

        void releaseBlockedCall() {
            blockedCallRelease.countDown();
        }
    }
}
