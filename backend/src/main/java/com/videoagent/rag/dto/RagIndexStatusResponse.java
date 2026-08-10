package com.videoagent.rag.dto;

public record RagIndexStatusResponse(
    String mode,
    String status,
    Integer chunkCount,
    String embeddingModel,
    Integer transcriptChars,
    String lastErrorCode,
    String lastErrorMessage
) {
}
