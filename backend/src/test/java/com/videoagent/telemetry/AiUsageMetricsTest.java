package com.videoagent.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

class AiUsageMetricsTest {

    @Test
    void shouldUseOnlyLowCardinalityTags() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AiUsageMetrics metrics = new AiUsageMetrics(meterRegistry);

        metrics.recordLogicalCall(
            "asr", "dashscope", "model", "sse", "success", "none", 12
        );

        Meter.Id id = meterRegistry.get("videoagent.ai.logical.calls").meter().getId();
        assertThat(id.getTags().stream().map(Tag::getKey)).containsExactlyInAnyOrder(
            "scope", "stage", "provider", "model", "mode", "outcome", "error_category"
        );
        assertThat(id.getTags().stream().map(Tag::getKey))
            .doesNotContain("taskId", "videoId", "generation", "retryCount", "userId");
    }

    @Test
    void shouldIgnoreMeterRegistryFailure() {
        MeterRegistry meterRegistry = mock(MeterRegistry.class);
        when(meterRegistry.counter(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Iterable<Tag>>any()))
            .thenThrow(new IllegalStateException("metrics unavailable"));
        AiUsageMetrics metrics = new AiUsageMetrics(meterRegistry);

        assertThatCode(() -> metrics.recordProviderRequest(
            "asr", "dashscope", "model", "sse", "failure", "ASR_REQUEST_FAILED", 12
        )).doesNotThrowAnyException();
    }
}
