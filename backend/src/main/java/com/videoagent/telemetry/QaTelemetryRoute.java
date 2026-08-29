package com.videoagent.telemetry;

/** Bounded QA routes used by logs and low-cardinality metric tags. */
public enum QaTelemetryRoute {
    BASIC_DIRECT("basic_direct"),
    BASIC_RAG("basic_rag"),
    AGENTIC("agentic"),
    AGENTIC_FALLBACK_BASIC("agentic_fallback_basic");

    private final String value;

    QaTelemetryRoute(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
