package com.videoagent.rag.chunk;

/**
 * Estimates the token scale of text for local chunk-boundary decisions.
 * Implementations are approximations unless they explicitly wrap the exact
 * tokenizer used by the configured embedding model.
 */
@FunctionalInterface
public interface TokenEstimator {

    int estimateTokens(String text);
}
