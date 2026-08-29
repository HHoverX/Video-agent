package com.videoagent.media;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

class FfmpegMediaProcessorDurationTest {

    @Test
    void shouldRoundUpFractionalSourceDurationToAvoidUnderstatingAnalysisLimit() {
        assertThat(FfmpegMediaProcessor.parseDurationSeconds("3600.1"))
            .isEqualTo(OptionalInt.of(3601));
    }
}
