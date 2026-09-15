package com.videoagent.rag.service;

import com.videoagent.rag.vector.MilvusTranscriptStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Best-effort Milvus cleanup after a video has been deleted from MySQL. The
 * MySQL delete is already committed; a Milvus failure only logs a warning and
 * never rolls back the business delete. This is a cross-store eventual cleanup,
 * not a distributed transaction.
 */
@Component
public class RagCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RagCleanupService.class);

    private final MilvusTranscriptStore transcriptStore;

    public RagCleanupService(MilvusTranscriptStore transcriptStore) {
        this.transcriptStore = transcriptStore;
    }

    public void cleanupVideo(long userId, long videoId) {
        try {
            transcriptStore.deleteByVideo(userId, videoId);
            log.info("[userId={}][videoId={}][stage=DELETE] rag vectors cleaned up best-effort",
                userId, videoId);
        } catch (RuntimeException exception) {
            log.warn("[userId={}][videoId={}][stage=DELETE] rag vector cleanup failed; "
                    + "business delete already committed: {}",
                userId, videoId, exception.getMessage());
        }
    }
}
