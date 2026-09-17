package com.videoagent.agent.memory;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class ConversationMemoryMetrics {

    private final MeterRegistry meterRegistry;

    public ConversationMemoryMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void increment(String event) {
        safely(() -> meterRegistry.counter("videoagent.memory." + event).increment());
    }

    public void recordRecentTurns(long count) {
        safely(() -> DistributionSummary.builder("videoagent.memory.recent.turns")
            .register(meterRegistry)
            .record(Math.max(0, count)));
    }

    public void recordSummaryChars(long count) {
        safely(() -> DistributionSummary.builder("videoagent.memory.summary.chars")
            .register(meterRegistry)
            .record(Math.max(0, count)));
    }

    public void recordCompactDuration(long durationNanos) {
        safely(() -> Timer.builder("videoagent.memory.compact.duration")
            .register(meterRegistry)
            .record(Duration.ofNanos(Math.max(0, durationNanos))));
    }

    private void safely(Runnable recorder) {
        try {
            recorder.run();
        } catch (RuntimeException ignored) {
            // Metrics must never affect Q&A or background compaction availability.
        }
    }
}
