package com.videoagent.asr;

import com.videoagent.telemetry.AnalysisTelemetryContext;

public interface AsrProvider {

    TranscriptionResult transcribe(AudioSource audioSource);

    default TranscriptionResult transcribe(
        AudioSource audioSource,
        AnalysisTelemetryContext telemetryContext
    ) {
        return transcribe(audioSource);
    }
}
