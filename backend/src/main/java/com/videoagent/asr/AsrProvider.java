package com.videoagent.asr;

public interface AsrProvider {

    TranscriptionResult transcribe(AudioSource audioSource);
}
