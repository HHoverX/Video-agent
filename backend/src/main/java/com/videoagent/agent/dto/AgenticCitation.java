package com.videoagent.agent.dto;

public record AgenticCitation(
    String sourceType,
    Long startMs,
    Long endMs,
    String text
) {
}
