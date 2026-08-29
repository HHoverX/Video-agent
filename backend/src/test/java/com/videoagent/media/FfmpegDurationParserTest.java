package com.videoagent.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

class FfmpegDurationParserTest {

    @Test
    void shouldRoundPositiveFractionalDurationUpWithMinimumOne() {
        assertThat(FfmpegMediaProcessor.parseDurationSeconds("0.1")).isEqualTo(OptionalInt.of(1));
        assertThat(FfmpegMediaProcessor.parseDurationSeconds("1.49")).isEqualTo(OptionalInt.of(2));
        assertThat(FfmpegMediaProcessor.parseDurationSeconds("1.5")).isEqualTo(OptionalInt.of(2));
    }

    @Test
    void shouldRejectInvalidOrOverflowingDuration() {
        assertThat(FfmpegMediaProcessor.parseDurationSeconds("not-a-number")).isEqualTo(OptionalInt.empty());
        assertThat(FfmpegMediaProcessor.parseDurationSeconds("Infinity")).isEqualTo(OptionalInt.empty());
        assertThat(FfmpegMediaProcessor.parseDurationSeconds("0")).isEqualTo(OptionalInt.empty());
        assertThat(FfmpegMediaProcessor.parseDurationSeconds("2147483648")).isEqualTo(OptionalInt.empty());
    }
}
