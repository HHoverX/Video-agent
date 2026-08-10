package com.videoagent.rag.dto;

public record QaCitation(
    long startMs,
    long endMs,
    String text
) {
}
