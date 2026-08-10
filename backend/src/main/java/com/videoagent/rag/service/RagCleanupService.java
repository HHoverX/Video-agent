package com.videoagent.rag.service;

import com.videoagent.rag.vector.QdrantVectorStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Best-effort Qdrant cleanup after a video has been deleted from MySQL. The
 * MySQL delete is already committed; a Qdrant failure only logs a warning and
 * never rolls back the business delete. This is a cross-store eventual cleanup,
 * not a distributed transaction.
 */
@Component
public class RagCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RagCleanupService.class);

    private final QdrantVectorStore vectorStore;

    public RagCleanupService(QdrantVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public void cleanupVideo(long userId, long videoId) {
        try {
            vectorStore.deleteByVideo(userId, videoId);
            log.info("[userId={}][videoId={}][stage=DELETE] rag vectors cleaned up best-effort",
                userId, videoId);
        } catch (RuntimeException exception) {
            log.warn("[userId={}][videoId={}][stage=DELETE] rag vector cleanup failed; "
                    + "business delete already committed: {}",
                userId, videoId, exception.getMessage());
        }
    }
}
