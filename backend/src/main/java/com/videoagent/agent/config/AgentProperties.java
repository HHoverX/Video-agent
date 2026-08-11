package com.videoagent.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agentic retrieval policy. These are engineering safety bounds, not
 * theoretically optimal parameters. They keep the agent bounded and auditable.
 */
@ConfigurationProperties(prefix = "videoagent.agent")
public record AgentProperties(
    String plannerProvider,
    Integer maxToolCalls,
    Long timeLookupWindowMs,
    Long maxTimeWindowMs,
    Integer maxEvidenceItems,
    Integer maxEvidenceChars,
    String plannerModel
) {

    private static final int DEFAULT_MAX_TOOL_CALLS = 4;
    private static final long DEFAULT_TIME_LOOKUP_WINDOW_MS = 15_000;
    private static final long DEFAULT_MAX_TIME_WINDOW_MS = 120_000;
    private static final int DEFAULT_MAX_EVIDENCE_ITEMS = 12;
    private static final int DEFAULT_MAX_EVIDENCE_CHARS = 12_000;

    private static final int MAX_TOOL_CALLS_LIMIT = 16;
    private static final long TIME_WINDOW_LIMIT_MS = 600_000;
    private static final int MAX_EVIDENCE_ITEMS_LIMIT = 64;
    private static final int MAX_EVIDENCE_CHARS_LIMIT = 65_536;

    public AgentProperties {
        plannerProvider = plannerProvider == null || plannerProvider.isBlank()
            ? "mock"
            : plannerProvider.strip().toLowerCase();
        maxToolCalls = defaultOrValidate(
            "AGENT_MAX_TOOL_CALLS", maxToolCalls, DEFAULT_MAX_TOOL_CALLS, MAX_TOOL_CALLS_LIMIT);
        timeLookupWindowMs = defaultOrValidate(
            "AGENT_TIME_LOOKUP_WINDOW_MS", timeLookupWindowMs,
            DEFAULT_TIME_LOOKUP_WINDOW_MS, TIME_WINDOW_LIMIT_MS);
        maxTimeWindowMs = defaultOrValidate(
            "AGENT_MAX_TIME_WINDOW_MS", maxTimeWindowMs,
            DEFAULT_MAX_TIME_WINDOW_MS, TIME_WINDOW_LIMIT_MS);
        maxEvidenceItems = defaultOrValidate(
            "AGENT_MAX_EVIDENCE_ITEMS", maxEvidenceItems,
            DEFAULT_MAX_EVIDENCE_ITEMS, MAX_EVIDENCE_ITEMS_LIMIT);
        maxEvidenceChars = defaultOrValidate(
            "AGENT_MAX_EVIDENCE_CHARS", maxEvidenceChars,
            DEFAULT_MAX_EVIDENCE_CHARS, MAX_EVIDENCE_CHARS_LIMIT);
        plannerModel = plannerModel == null || plannerModel.isBlank() ? "" : plannerModel.strip();
        if (maxTimeWindowMs < timeLookupWindowMs) {
            throw new IllegalArgumentException(
                "AGENT_MAX_TIME_WINDOW_MS must be >= AGENT_TIME_LOOKUP_WINDOW_MS"
            );
        }
    }

    private static int defaultOrValidate(String name, Integer value, int defaultValue, int limit) {
        if (value == null) {
            return defaultValue;
        }
        if (value <= 0 || value > limit) {
            throw new IllegalArgumentException(name + " must be between 1 and " + limit);
        }
        return value;
    }

    private static long defaultOrValidate(String name, Long value, long defaultValue, long limit) {
        if (value == null) {
            return defaultValue;
        }
        if (value <= 0 || value > limit) {
            throw new IllegalArgumentException(name + " must be between 1 and " + limit);
        }
        return value;
    }
}
