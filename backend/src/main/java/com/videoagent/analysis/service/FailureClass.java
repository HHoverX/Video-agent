package com.videoagent.analysis.service;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import java.util.Set;

/**
 * Explicit allowlist of transient, re-runnable failures. Everything not listed
 * here — including unknown/blank error codes and programming errors — is
 * NON_RETRYABLE and fails the task immediately.
 */
public enum FailureClass {
    RETRYABLE,
    NON_RETRYABLE;

    private static final Set<String> RETRYABLE_CODES = Set.of(
        ErrorCode.ANALYSIS_DISPATCH_FAILED.name(),
        ErrorCode.MEDIA_TEMP_FILE_ERROR.name(),
        ErrorCode.FFMPEG_TIMEOUT.name(),
        ErrorCode.ASR_REQUEST_FAILED.name(),
        ErrorCode.ASR_TIMEOUT.name(),
        ErrorCode.LLM_SUMMARY_FAILED.name(),
        ErrorCode.TRANSCRIPTION_FAILED.name(),
        ErrorCode.SUMMARY_PERSISTENCE_FAILED.name(),
        ErrorCode.STORAGE_ERROR.name()
    );

    private static final Set<String> NON_RETRYABLE_CODES = Set.of(
        ErrorCode.VIDEO_AUDIO_STREAM_NOT_FOUND.name(),
        ErrorCode.FFMPEG_EXECUTION_FAILED.name(),
        ErrorCode.FFMPEG_OUTPUT_MISSING.name(),
        ErrorCode.ASR_PROVIDER_REJECTED.name(),
        ErrorCode.ASR_RESPONSE_INVALID.name(),
        ErrorCode.ASR_INPUT_TOO_LARGE.name(),
        ErrorCode.LLM_PROVIDER_REJECTED.name(),
        ErrorCode.LLM_SUMMARY_INVALID.name(),
        ErrorCode.INTERNAL_ANALYSIS_ERROR.name(),
        ErrorCode.ANALYSIS_DISPATCH_EXHAUSTED.name(),
        ErrorCode.ANALYSIS_RETRY_EXHAUSTED.name()
    );

    public static FailureClass of(VideoAgentException exception) {
        return of(exception.errorCode().name());
    }

    public static FailureClass of(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) {
            return NON_RETRYABLE;
        }
        if (RETRYABLE_CODES.contains(errorCode)) {
            return RETRYABLE;
        }
        return NON_RETRYABLE;
    }
}
