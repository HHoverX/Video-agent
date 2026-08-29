package com.videoagent.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.DistributionSummary;

import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Low-cardinality, best-effort metrics for externally-backed AI work.
 * Task/request identifiers and all content remain in logs rather than tags.
 */
@Component
public final class AiUsageMetrics {

    private static final String ANALYSIS_SCOPE = "analysis";

    private final MeterRegistry meterRegistry;

    public AiUsageMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public static AiUsageMetrics noop() {
        return new AiUsageMetrics(null);
    }

    public void recordLogicalCall(
        String stage,
        String provider,
        String model,
        String mode,
        String outcome,
        String errorCategory,
        long durationMs
    ) {
        recordLogicalCall(ANALYSIS_SCOPE, stage, provider, model, mode, outcome, errorCategory, durationMs);
    }

    public void recordLogicalCall(
        String scope,
        String stage,
        String provider,
        String model,
        String mode,
        String outcome,
        String errorCategory,
        long durationMs
    ) {
        safely(() -> {
            Tags tags = callTags(scope, stage, provider, model, mode, outcome, errorCategory);
            meterRegistry.counter("videoagent.ai.logical.calls", tags).increment();
            Timer.builder("videoagent.ai.logical.duration")
                .tags(tags)
                .register(meterRegistry)
                .record(Duration.ofMillis(nonNegative(durationMs)));
        });
    }

    public void recordProviderRequest(
        String stage,
        String provider,
        String model,
        String mode,
        String outcome,
        String errorCategory,
        long durationMs
    ) {
        recordProviderRequest(ANALYSIS_SCOPE, stage, provider, model, mode, outcome, errorCategory, durationMs);
    }

    public void recordProviderRequest(
        String scope,
        String stage,
        String provider,
        String model,
        String mode,
        String outcome,
        String errorCategory,
        long durationMs
    ) {
        safely(() -> {
            Tags tags = callTags(scope, stage, provider, model, mode, outcome, errorCategory);
            meterRegistry.counter("videoagent.ai.provider.requests", tags).increment();
            Timer.builder("videoagent.ai.provider.request.duration")
                .tags(tags)
                .register(meterRegistry)
                .record(Duration.ofMillis(nonNegative(durationMs)));
        });
    }

    public void recordInputScale(
        String stage,
        String provider,
        String model,
        String mode,
        String inputType,
        long value
    ) {
        recordInputScale(ANALYSIS_SCOPE, stage, provider, model, mode, inputType, value);
    }

    public void recordInputScale(
        String scope,
        String stage,
        String provider,
        String model,
        String mode,
        String inputType,
        long value
    ) {
        safely(() -> DistributionSummary.builder("videoagent.ai.input.scale")
            .tags(Tags.of(
                "scope", tag(scope),
                "stage", tag(stage),
                "provider", tag(provider),
                "model", tag(model),
                "mode", tag(mode),
                "input_type", tag(inputType)
            ))
            .register(meterRegistry)
            .record(nonNegative(value)));
    }

    private Tags callTags(
        String scope,
        String stage,
        String provider,
        String model,
        String mode,
        String outcome,
        String errorCategory
    ) {
        return Tags.of(
            "scope", tag(scope),
            "stage", tag(stage),
            "provider", tag(provider),
            "model", tag(model),
            "mode", tag(mode),
            "outcome", tag(outcome),
            "error_category", tag(errorCategory)
        );
    }

    private void safely(Runnable recording) {
        if (meterRegistry == null) {
            return;
        }
        try {
            recording.run();
        } catch (RuntimeException ignored) {
            // Telemetry must never alter an AI provider or AnalysisTask result.
        }
    }

    private static long nonNegative(long value) {
        return Math.max(0L, value);
    }

    private static String tag(String value) {
        return value == null || value.isBlank() ? "none" : value.strip();
    }
}
