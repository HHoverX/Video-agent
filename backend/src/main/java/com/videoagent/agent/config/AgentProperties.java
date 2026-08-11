package com.videoagent.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agentic retrieval policy. These are engineering safety bounds, not
 * theoretically optimal parameters. They keep the agent bounded and auditable.
 */
@ConfigurationProperties(prefix = "videoagent.agent")
public record AgentProperties(
    String plannerProvider,
    int maxToolCalls,
    long timeLookupWindowMs,
    long maxTimeWindowMs,
    int maxEvidenceItems,
    int maxEvidenceChars,
    String plannerModel
) {

    public AgentProperties {
        plannerProvider = plannerProvider == null || plannerProvider.isBlank()
            ? "mock"
            : plannerProvider.strip().toLowerCase();
        maxToolCalls = maxToolCalls <= 0 ? 4 : maxToolCalls;
        timeLookupWindowMs = timeLookupWindowMs <= 0 ? 15_000 : timeLookupWindowMs;
        maxTimeWindowMs = maxTimeWindowMs <= 0 ? 120_000 : maxTimeWindowMs;
        maxEvidenceItems = maxEvidenceItems <= 0 ? 12 : maxEvidenceItems;
        maxEvidenceChars = maxEvidenceChars <= 0 ? 12_000 : maxEvidenceChars;
        plannerModel = plannerModel == null || plannerModel.isBlank() ? "" : plannerModel.strip();
        if (maxTimeWindowMs < timeLookupWindowMs) {
            throw new IllegalArgumentException(
                "AGENT_MAX_TIME_WINDOW_MS must be >= AGENT_TIME_LOOKUP_WINDOW_MS"
            );
        }
    }
}
