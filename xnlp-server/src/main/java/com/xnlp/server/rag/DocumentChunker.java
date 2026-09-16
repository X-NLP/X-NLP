package com.xnlp.server.rag;

import com.xnlp.core.rag.ChunkPolicy;
import com.xnlp.core.rag.KnowledgeChunk;
import com.xnlp.core.rag.KnowledgeDocument;

import java.util.List;

/** Splits source documents into deterministic, source-addressable chunks. */
public interface DocumentChunker {

    List<KnowledgeChunk> chunk(KnowledgeDocument document, ChunkPolicy policy);
}
