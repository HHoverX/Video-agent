package com.videoagent.agent.evidence;

import java.util.List;

/**
 * Normalized evidence passed to the answer synthesizer. Every item has a
 * request-local evidenceId (E1, E2, ...) assigned by the executor; the LLM can
 * only cite these IDs, and the backend maps them back to real persisted data.
 * Timestamps always come from MySQL transcript rows or Qdrant metadata.
 */
public record EvidenceItem(
    String evidenceId,
    EvidenceSourceType sourceType,
    String text,
    Long startMs,
    Long endMs,
    Integer segmentIndex,
    Integer chunkIndex,
    List<Integer> sourceSegmentIndexes,
    Float score
) {
    public EvidenceItem {
        sourceSegmentIndexes = sourceSegmentIndexes == null
            ? List.of()
            : List.copyOf(sourceSegmentIndexes);
    }

    /**
     * Stable identity used for de-duplication across tools: transcript time and
     * search evidence are identified by segment/chunk identity.
     */
    public String dedupKey() {
        if (sourceType == EvidenceSourceType.SUMMARY) {
            return "summary";
        }
        if (segmentIndex != null) {
            return sourceType.name() + ":segment:" + segmentIndex;
        }
        if (chunkIndex != null) {
            return sourceType.name() + ":chunk:" + chunkIndex;
        }
        return evidenceId;
    }
}
