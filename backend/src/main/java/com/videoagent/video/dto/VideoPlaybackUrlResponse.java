package com.videoagent.video.dto;

import java.time.Instant;

public record VideoPlaybackUrlResponse(String url, Instant expiresAt) {}
