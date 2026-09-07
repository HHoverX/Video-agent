package com.videoagent.rag.rerank;

import com.videoagent.rag.retrieval.HybridCandidate;
import java.util.List;

public interface TranscriptReranker {
    boolean enabled();
    List<HybridCandidate> rerank(String query, List<HybridCandidate> candidates);
}
