package com.videoagent.analysis.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

@ConfigurationProperties(prefix = "videoagent.analysis.protection")
public record AnalysisProtectionProperties(
    RateLimit rateLimit,
    Integer maxActivePerUser
) {
    @ConstructorBinding
    public AnalysisProtectionProperties {
        rateLimit = rateLimit == null ? new RateLimit(null, null, null, null) : rateLimit;
        maxActivePerUser = maxActivePerUser == null ? 3 : maxActivePerUser;
        if (maxActivePerUser < 1) {
            throw new IllegalArgumentException("videoagent.analysis.protection.max-active-per-user must be positive");
        }
    }

    public record RateLimit(
        Integer capacity,
        Integer refillTokens,
        Duration refillPeriod,
        Integer costPerRequest
    ) {
        public RateLimit {
            capacity = capacity == null ? 5 : capacity;
            refillTokens = refillTokens == null ? 1 : refillTokens;
            refillPeriod = refillPeriod == null ? Duration.ofSeconds(30) : refillPeriod;
            costPerRequest = costPerRequest == null ? 1 : costPerRequest;
            if (capacity < 1 || refillTokens < 1 || refillPeriod.isZero() || refillPeriod.isNegative()
                || costPerRequest < 1 || costPerRequest > capacity) {
                throw new IllegalArgumentException("videoagent.analysis.protection.rate-limit values are invalid");
            }
        }
    }
}
