package com.videoagent.agent.planner;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record PlannerAiResponse(
    @JsonProperty("intent") String intent,
    @JsonProperty("strategyLabel") String strategyLabel,
    @JsonProperty("actions") List<PlannerAction> actions
) {
}
