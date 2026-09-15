package com.xnlp.core.rag;

import java.util.List;

/** Optional second-stage ranking SPI. */
public interface RetrievalReranker {

    String name();

    List<RetrievalMatch> rerank(String query, List<RetrievalMatch> candidates, int topN);
}
