package com.videoagent.agent.planner;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PlannerAction(
    @JsonProperty("tool") String tool,
    @JsonProperty("query") String query,
    @JsonProperty("timeMs") Long timeMs,
    @JsonProperty("windowMs") Long windowMs
) {
}
