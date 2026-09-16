package com.videoagent.rag.dto;

public record RagIndexStatusResponse(
    String status,
    Integer chunkCount,
    String embeddingModel,
    String lastErrorCode,
    String lastErrorMessage
) {
}
