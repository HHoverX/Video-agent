package com.videoagent.common.health;

import java.time.Instant;

public record HealthResponse(String status, String application, Instant timestamp) {
}

